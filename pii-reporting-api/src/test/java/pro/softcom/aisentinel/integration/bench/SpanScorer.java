package pro.softcom.aisentinel.integration.bench;

import java.util.List;
import java.util.Map;

/**
 * Pure span-level scorer (no Docker, no Spring) — the verifiable core of the
 * benchmark. Computes precision/recall/F1 by exact char-offset match, in a
 * strict (label-aware) and a type-agnostic (label-free) view.
 *
 * <p>Scoring rules (spec §"Étapes" 4 and §"Points d'attention"):
 * <ul>
 *   <li><b>TP</b>: a gold span matched by a finding with the same boundaries
 *       (and, in strict mode, the same canonical label).</li>
 *   <li><b>FN</b>: a gold span no finding matched.</li>
 *   <li><b>FP</b>: a finding that matched no gold span AND does not overlap any
 *       ignore zone. A finding overlapping an ignore zone is dropped entirely:
 *       the dataset marked that region as PII of a non-mappable type, so the
 *       detector is neither rewarded nor penalised there.</li>
 * </ul>
 * Each finding is consumed by at most one gold span; surplus findings on an
 * already-matched span count as false positives (over-detection).
 */
public final class SpanScorer {

    private SpanScorer() {
    }

    public static ScoreResult score(List<GoldDoc> goldDocs, Map<String, List<Finding>> findingsByDocId) {
        ScoreResult result = new ScoreResult();
        for (GoldDoc doc : goldDocs) {
            List<Finding> findings = findingsByDocId.getOrDefault(doc.id(), List.of());
            scoreStrict(doc, findings, result);
            scoreTypeAgnostic(doc, findings, result);
        }
        return result;
    }

    private static void scoreStrict(GoldDoc doc, List<Finding> findings, ScoreResult result) {
        boolean[] used = new boolean[findings.size()];
        for (GoldSpan gold : doc.spans()) {
            int match = indexOfMatch(findings, used, gold, true);
            if (match >= 0) {
                used[match] = true;
                result.strictOverall.addTp();
                result.labelBucket(gold.label()).addTp();
                result.categoryBucket(gold.label()).addTp();
            } else {
                result.strictOverall.addFn();
                result.labelBucket(gold.label()).addFn();
                result.categoryBucket(gold.label()).addFn();
                result.addFalseNegative(new ScoreResult.Example(
                    doc.id(), doc.dataset(), gold.start(), gold.end(),
                    gold.label(), null, null, snippet(doc.text(), gold.start(), gold.end())));
            }
        }
        for (int i = 0; i < findings.size(); i++) {
            if (used[i]) {
                continue;
            }
            Finding f = findings.get(i);
            if (overlapsIgnoreZone(doc, f)) {
                continue;
            }
            result.strictOverall.addFp();
            result.labelBucket(f.canonical()).addFp();
            result.categoryBucket(f.canonical()).addFp();
            result.addFalsePositive(new ScoreResult.Example(
                doc.id(), doc.dataset(), f.start(), f.end(),
                null, f.canonical(), f.source(), snippet(doc.text(), f.start(), f.end())));
        }
    }

    private static void scoreTypeAgnostic(GoldDoc doc, List<Finding> findings, ScoreResult result) {
        boolean[] used = new boolean[findings.size()];
        for (GoldSpan gold : doc.spans()) {
            int match = indexOfMatch(findings, used, gold, false);
            if (match >= 0) {
                used[match] = true;
                result.typeAgnosticOverall.addTp();
            } else {
                result.typeAgnosticOverall.addFn();
            }
        }
        for (int i = 0; i < findings.size(); i++) {
            if (used[i]) {
                continue;
            }
            if (overlapsIgnoreZone(doc, findings.get(i))) {
                continue;
            }
            result.typeAgnosticOverall.addFp();
        }
    }

    /**
     * First unused finding whose boundaries equal {@code gold}'s. In strict mode
     * the canonical label must also match.
     */
    private static int indexOfMatch(List<Finding> findings, boolean[] used, GoldSpan gold, boolean labelAware) {
        for (int i = 0; i < findings.size(); i++) {
            if (used[i]) {
                continue;
            }
            Finding f = findings.get(i);
            if (!gold.sameBoundaries(f.start(), f.end())) {
                continue;
            }
            if (labelAware && !gold.label().equals(f.canonical())) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean overlapsIgnoreZone(GoldDoc doc, Finding f) {
        for (GoldSpan ignore : doc.ignoreSpans()) {
            if (ignore.overlaps(f.start(), f.end())) {
                return true;
            }
        }
        return false;
    }

    private static String snippet(String text, int start, int end) {
        if (text == null) {
            return "";
        }
        int s = Math.max(0, Math.min(start, text.length()));
        int e = Math.max(s, Math.min(end, text.length()));
        return text.substring(s, e).replace('\n', ' ').replace('\r', ' ');
    }
}
