package pro.softcom.aisentinel.integration.bench;

/**
 * One PII entity predicted by a generative LLM extractor, projected onto the
 * canonical concept space. Extractors return values, not offsets, so a
 * prediction carries the raw value and is scored by value (see {@link ValueScorer}).
 *
 * @param canonical canonical concept (never {@code IGNORE}/null — out-of-scope
 *                  predictions are dropped before scoring)
 * @param value     the entity text exactly as returned by the model
 */
public record Prediction(String canonical, String value) {
}
