package pro.softcom.aisentinel.integration.bench;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Value-level scorer for generative LLM extractors, which return PII <em>values</em>
 * (not char offsets). For each document it matches predicted (canonical, value)
 * pairs against the gold spans' (canonical, value) as multisets:
 * <ul>
 *   <li><b>strict</b>  — same canonical concept AND same normalised value;</li>
 *   <li><b>type-agnostic</b> — same normalised value, label ignored (the gap to
 *       strict isolates typing errors).</li>
 * </ul>
 * Values are normalised (trim, lower-case, collapse whitespace) before matching.
 * Each prediction/gold value is consumed at most once, so duplicates are handled
 * as multisets (a value occurring twice in gold needs two predictions for 2 TP).
 *
 * <p>Predictions must already be in-scope (canonical non-null); out-of-scope
 * (IGNORE) ones are dropped by the caller via {@link ExtractorConceptMap}.
 */
final class ValueScorer {

    private ValueScorer() {
    }

    static ScoreResult score(List<GoldDoc> goldDocs, Map<String, List<Prediction>> predsByDocId) {
        ScoreResult result = new ScoreResult();
        for (GoldDoc doc : goldDocs) {
            List<Prediction> preds = predsByDocId.getOrDefault(doc.id(), List.of());
            scoreStrict(doc, preds, result);
            scoreTypeAgnostic(doc, preds, result);
        }
        return result;
    }

    private static void scoreStrict(GoldDoc doc, List<Prediction> preds, ScoreResult result) {
        boolean[] used = new boolean[preds.size()];
        for (GoldSpan gold : doc.spans()) {
            String goldValue = normalize(value(doc, gold));
            int match = indexOfMatch(preds, used, gold.label(), goldValue, true);
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
                    doc.id(), doc.dataset(), -1, -1, gold.label(), null, null, value(doc, gold)));
            }
        }
        for (int i = 0; i < preds.size(); i++) {
            if (used[i]) {
                continue;
            }
            Prediction p = preds.get(i);
            result.strictOverall.addFp();
            result.labelBucket(p.canonical()).addFp();
            result.categoryBucket(p.canonical()).addFp();
            result.addFalsePositive(new ScoreResult.Example(
                doc.id(), doc.dataset(), -1, -1, null, p.canonical(), null, p.value()));
        }
    }

    private static void scoreTypeAgnostic(GoldDoc doc, List<Prediction> preds, ScoreResult result) {
        boolean[] used = new boolean[preds.size()];
        for (GoldSpan gold : doc.spans()) {
            String goldValue = normalize(value(doc, gold));
            int match = indexOfMatch(preds, used, gold.label(), goldValue, false);
            if (match >= 0) {
                used[match] = true;
                result.typeAgnosticOverall.addTp();
            } else {
                result.typeAgnosticOverall.addFn();
            }
        }
        for (int i = 0; i < preds.size(); i++) {
            if (!used[i]) {
                result.typeAgnosticOverall.addFp();
            }
        }
    }

    private static int indexOfMatch(List<Prediction> preds, boolean[] used, String canonical, String normValue,
                                    boolean labelAware) {
        for (int i = 0; i < preds.size(); i++) {
            if (used[i]) {
                continue;
            }
            Prediction p = preds.get(i);
            if (!normValue.equals(normalize(p.value()))) {
                continue;
            }
            if (labelAware && !canonical.equals(p.canonical())) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static String value(GoldDoc doc, GoldSpan span) {
        String text = doc.text();
        int s = Math.max(0, Math.min(span.start(), text.length()));
        int e = Math.max(s, Math.min(span.end(), text.length()));
        return text.substring(s, e);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
