package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Replaces plaintext PII values with redaction tokens directly inside Confluence
 * storage-format XHTML without corrupting the markup (macros, CDATA, entities).
 *
 * <p>Detector values come from the extracted text and almost never match the raw
 * markup byte-for-byte, so matching happens on a normalized concatenation of the
 * document text nodes (entities decoded by the XML parser, whitespace variants
 * collapsed, NFC). Matches are then re-projected onto the original nodes: the token
 * lands in the first covered node and the covered fragments of following nodes are
 * removed, which handles values split across inline formatting or table cells.</p>
 *
 * <p>All storage-format knowledge is confined to this class; callers hand in plain
 * (value, token) pairs. Values are never logged and never appear in outcomes, which
 * are correlated to the input by position.</p>
 */
@Component
public class StorageContentRedactor {

    private static final Set<String> NON_CONTENT_TAGS = Set.of("ac:parameter", "ac:image", "ac:emoticon");
    private static final Set<String> HARD_BLOCK_TAGS = Set.of(
        "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6",
        "ul", "ol", "li", "dl", "dt", "dd", "table", "tr", "blockquote", "pre", "br",
        "header", "footer", "nav", "aside", "ac:rich-text-body", "ac:layout-cell", "ac:task-body");
    private static final Set<String> SOFT_BLOCK_TAGS = Set.of("td", "th");
    private static final String MAILTO_SCHEME = "mailto:";
    private static final String TEL_SCHEME = "tel:";

    /**
     * Applies every replacement sequentially on the same document; tokens already
     * written never re-match. Returns one outcome per replacement, in input order.
     * When nothing matches, the input string is returned verbatim.
     */
    public RedactionResult redact(String storageXhtml, List<ValueReplacement> replacements,
                                  RedactionGuardConfig guardConfig) {
        Document document = parseStorage(storageXhtml);
        List<ValueOutcome> outcomes = new ArrayList<>();
        int totalReplaced = 0;
        for (ValueReplacement value : replacements) {
            int occurrences = redactValue(document, value, guardConfig);
            outcomes.add(new ValueOutcome(occurrences));
            totalReplaced += occurrences;
        }
        String redactedXhtml = totalReplaced > 0 ? document.outerHtml() : storageXhtml;
        return new RedactionResult(redactedXhtml, outcomes);
    }

    private static Document parseStorage(String storageXhtml) {
        Document document = Jsoup.parse(storageXhtml, "", Parser.xmlParser());
        document.outputSettings(new Document.OutputSettings().prettyPrint(false));
        return document;
    }

    private int redactValue(Document document, ValueReplacement value, RedactionGuardConfig guards) {
        String needle = NormalizedText.normalizeValue(value.plaintextValue());
        if (needle.length() < guards.minValueLength()) {
            return 0;
        }
        Replacement replacement = new Replacement(needle, value.token(), guards.wordBoundaries());
        int occurrences = redactTextFlow(document, replacement);
        if (occurrences > 0) {
            redactResidualCarriers(document, replacement);
        }
        return occurrences;
    }

    private int redactTextFlow(Document document, Replacement replacement) {
        List<Segment> segments = new ArrayList<>();
        NormalizedText stream = new NormalizedText();
        collectContent(document, segments, stream);
        List<Match> matches = findMatches(stream.text(), replacement);
        Map<Integer, TreeMap<Integer, TextEdit>> editsPerSegment = new LinkedHashMap<>();
        matches.forEach(match -> planMatchEdits(stream, match, editsPerSegment));
        applyEdits(segments, editsPerSegment, replacement.token());
        return matches.size();
    }

    private void collectContent(Node node, List<Segment> segments, NormalizedText stream) {
        if (node instanceof Element element) {
            visitElement(element, segments, stream);
        } else if (node instanceof TextNode textNode) {
            // CDataNode extends TextNode and is included on purpose: the production text
            // extraction surfaces CDATA content (spike-verified), so findings can
            // originate from code-macro bodies and plain-text link bodies.
            String content = NormalizedText.nfc(textNode.getWholeText());
            segments.add(new Segment(textNode, content));
            stream.append(segments.size() - 1, content);
        }
    }

    private void visitElement(Element element, List<Segment> segments, NormalizedText stream) {
        String tag = element.tagName().toLowerCase(Locale.ROOT);
        if (NON_CONTENT_TAGS.contains(tag) || tag.startsWith("ri:")) {
            return;
        }
        emitSeparator(stream, tag);
        element.childNodes().forEach(child -> collectContent(child, segments, stream));
        emitSeparator(stream, tag);
    }

    private static void emitSeparator(NormalizedText stream, String tag) {
        if (HARD_BLOCK_TAGS.contains(tag)) {
            stream.hardSeparator();
        } else if (SOFT_BLOCK_TAGS.contains(tag)) {
            stream.softSeparator();
        }
    }

    private static List<Match> findMatches(String haystack, Replacement replacement) {
        List<Match> matches = new ArrayList<>();
        String needle = replacement.needle();
        int from = 0;
        int index;
        while ((index = haystack.indexOf(needle, from)) >= 0) {
            if (!replacement.wordBounded() || isWordBounded(haystack, index, needle.length())) {
                matches.add(new Match(index, needle.length()));
                from = index + needle.length();
            } else {
                from = index + 1;
            }
        }
        return matches;
    }

    private static boolean isWordBounded(String haystack, int start, int length) {
        boolean beforeOk = start == 0 || !Character.isLetterOrDigit(haystack.charAt(start - 1));
        int end = start + length;
        boolean afterOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
        return beforeOk && afterOk;
    }

    private static void planMatchEdits(NormalizedText stream, Match match,
                                       Map<Integer, TreeMap<Integer, TextEdit>> editsPerSegment) {
        Map<Integer, int[]> rawSpans = new TreeMap<>();
        int firstSegment = -1;
        for (int position = match.start(); position < match.start() + match.length(); position++) {
            int segment = stream.segmentAt(position);
            if (segment < 0) {
                continue;
            }
            if (firstSegment < 0) {
                firstSegment = segment;
            }
            widenSpan(rawSpans, segment, stream.rawOffsetAt(position));
        }
        registerEdits(rawSpans, firstSegment, editsPerSegment);
    }

    private static void widenSpan(Map<Integer, int[]> rawSpans, int segment, int rawOffset) {
        int[] span = rawSpans.computeIfAbsent(segment, key -> new int[]{rawOffset, rawOffset + 1});
        span[0] = Math.min(span[0], rawOffset);
        span[1] = Math.max(span[1], rawOffset + 1);
    }

    private static void registerEdits(Map<Integer, int[]> rawSpans, int firstSegment,
                                      Map<Integer, TreeMap<Integer, TextEdit>> editsPerSegment) {
        rawSpans.forEach((segment, span) -> {
            boolean insertToken = segment == firstSegment;
            editsPerSegment.computeIfAbsent(segment, key -> new TreeMap<>())
                .put(span[0], new TextEdit(span[1], insertToken));
        });
    }

    private static void applyEdits(List<Segment> segments, Map<Integer, TreeMap<Integer, TextEdit>> editsPerSegment,
                                   String token) {
        editsPerSegment.forEach((segmentIndex, edits) -> {
            Segment segment = segments.get(segmentIndex);
            StringBuilder content = new StringBuilder(segment.content());
            edits.descendingMap().forEach((rawStart, edit) ->
                content.replace(rawStart, edit.rawEnd(), edit.insertToken() ? token : ""));
            segment.node().text(content.toString());
        });
    }

    /**
     * Values confirmed in the text flow may survive in carriers excluded from detection:
     * mailto/tel hrefs and macro parameters duplicating a body value. Those residuals are
     * redacted with the same normalized, guarded matching.
     */
    private static void redactResidualCarriers(Document document, Replacement replacement) {
        document.getElementsByTag("a").forEach(link -> redactSensitiveHref(link, replacement));
        document.getElementsByTag("ac:parameter")
            .forEach(parameter -> redactParameterText(parameter, replacement));
    }

    private static void redactSensitiveHref(Element link, Replacement replacement) {
        String href = link.attr("href");
        if (href.startsWith(MAILTO_SCHEME) || href.startsWith(TEL_SCHEME)) {
            link.attr("href", redactPlainString(href, replacement));
        }
    }

    private static void redactParameterText(Element parameter, Replacement replacement) {
        parameter.textNodes().forEach(textNode -> {
            String redacted = redactPlainString(textNode.getWholeText(), replacement);
            textNode.text(redacted);
        });
    }

    private static String redactPlainString(String raw, Replacement replacement) {
        String content = NormalizedText.nfc(raw);
        NormalizedText stream = new NormalizedText();
        stream.append(0, content);
        List<Match> matches = findMatches(stream.text(), replacement);
        if (matches.isEmpty()) {
            return raw;
        }
        StringBuilder result = new StringBuilder(content);
        for (int i = matches.size() - 1; i >= 0; i--) {
            Match match = matches.get(i);
            int rawStart = stream.rawOffsetAt(match.start());
            int rawEnd = stream.rawOffsetAt(match.start() + match.length() - 1) + 1;
            result.replace(rawStart, rawEnd, replacement.token());
        }
        return result.toString();
    }

    public record ValueReplacement(String plaintextValue, String token) {
    }

    public record RedactionGuardConfig(int minValueLength, boolean wordBoundaries) {

        public static RedactionGuardConfig defaults() {
            return new RedactionGuardConfig(4, true);
        }
    }

    public record ValueOutcome(int occurrencesReplaced) {

        public boolean found() {
            return occurrencesReplaced > 0;
        }
    }

    public record RedactionResult(String redactedXhtml, List<ValueOutcome> outcomes) {
    }

    private record Replacement(String needle, String token, boolean wordBounded) {
    }

    private record Segment(TextNode node, String content) {
    }

    private record Match(int start, int length) {
    }

    private record TextEdit(int rawEnd, boolean insertToken) {
    }
}
