package pro.softcom.aisentinel.integration.bench;

import java.util.List;

/**
 * One ground-truth document produced by {@code build_datasets.py}.
 *
 * @param id          stable document id (dataset-prefixed)
 * @param dataset     source dataset key ({@code gretelai}, {@code ai4privacy}, {@code synthetic})
 * @param lang        language code/name of the text
 * @param text        the raw text fed verbatim to the detector pipeline
 * @param spans       gold spans carrying a CANONICAL concept label — these are
 *                    the positives the detectors are scored against
 * @param ignoreSpans spans the dataset marked as PII but whose label has no
 *                    in-scope (enabled) detector equivalent. They are neither
 *                    true positives to find (no FN) nor regions a finding is
 *                    penalised on (no FP) — see {@link SpanScorer}.
 */
public record GoldDoc(
    String id,
    String dataset,
    String lang,
    String text,
    List<GoldSpan> spans,
    List<GoldSpan> ignoreSpans
) {
    public GoldDoc {
        spans = spans == null ? List.of() : List.copyOf(spans);
        ignoreSpans = ignoreSpans == null ? List.of() : List.copyOf(ignoreSpans);
    }
}
