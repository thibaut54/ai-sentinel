package pro.softcom.aisentinel.integration.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the value-level extractor scorer and the extractor concept map.
 * No network — verifiable core of the LLM extractor comparison.
 */
class ValueScorerTest {

    private static GoldSpan goldOf(String text, String value, String label) {
        int start = text.indexOf(value);
        if (start < 0) {
            throw new IllegalArgumentException("value not in text: " + value);
        }
        return new GoldSpan(start, start + value.length(), label);
    }

    private static GoldDoc doc(String text, List<GoldSpan> spans) {
        return new GoldDoc("doc-1", "gretelai", "English", text, spans, List.of());
    }

    @Test
    void Should_CountTruePositive_When_CanonicalAndNormalizedValueMatch() {
        String text = "IBAN GB29NWBK60161331926819 on file";
        GoldDoc d = doc(text, List.of(goldOf(text, "GB29NWBK60161331926819", "IBAN")));
        // prediction with different case + surrounding spaces -> normalised match
        Map<String, List<Prediction>> preds = Map.of("doc-1",
            List.of(new Prediction("IBAN", "  gb29nwbk60161331926819 ")));

        ScoreResult r = ValueScorer.score(List.of(d), preds);

        assertThat(r.strictOverall().tp()).isEqualTo(1);
        assertThat(r.strictOverall().fp()).isZero();
        assertThat(r.strictOverall().fn()).isZero();
        assertThat(r.strictByLabel().get("IBAN").tp()).isEqualTo(1);
    }

    @Test
    void Should_CountFalseNegative_When_GoldValueNotPredicted() {
        String text = "SSN 123-45-6789 recorded";
        GoldDoc d = doc(text, List.of(goldOf(text, "123-45-6789", "NATIONAL_ID_NUMBER")));

        ScoreResult r = ValueScorer.score(List.of(d), Map.of());

        assertThat(r.strictOverall().fn()).isEqualTo(1);
        assertThat(r.strictOverall().recall()).isZero();
        assertThat(r.falseNegatives()).hasSize(1);
        assertThat(r.falseNegatives().get(0).snippet()).isEqualTo("123-45-6789");
    }

    @Test
    void Should_CountFalsePositive_When_PredictionNotInGold() {
        String text = "nothing sensitive here";
        GoldDoc d = doc(text, List.of());
        Map<String, List<Prediction>> preds = Map.of("doc-1",
            List.of(new Prediction("PASSWORD", "hunter2")));

        ScoreResult r = ValueScorer.score(List.of(d), preds);

        assertThat(r.strictOverall().fp()).isEqualTo(1);
        assertThat(r.falsePositives().get(0).findingLabel()).isEqualTo("PASSWORD");
        assertThat(r.falsePositives().get(0).snippet()).isEqualTo("hunter2");
    }

    @Test
    void Should_CountTypingError_When_ValueMatchesButCanonicalDiffers() {
        String text = "value 4111111111111111 end";
        GoldDoc d = doc(text, List.of(goldOf(text, "4111111111111111", "CARD_NUMBER")));
        Map<String, List<Prediction>> preds = Map.of("doc-1",
            List.of(new Prediction("BANK_ACCOUNT", "4111111111111111")));

        ScoreResult r = ValueScorer.score(List.of(d), preds);

        assertThat(r.strictOverall().tp()).isZero();
        assertThat(r.strictOverall().fn()).isEqualTo(1);
        assertThat(r.strictOverall().fp()).isEqualTo(1);
        assertThat(r.typeAgnosticOverall().tp()).isEqualTo(1);
        assertThat(r.typeAgnosticOverall().fp()).isZero();
        assertThat(r.typingErrors()).isEqualTo(1);
    }

    @Test
    void Should_HandleDuplicateValuesAsMultiset_When_ValueOccursTwice() {
        String text = "ip 10.0.0.1 and 10.0.0.1 again";
        // two gold occurrences of the same value
        GoldSpan first = new GoldSpan(text.indexOf("10.0.0.1"), text.indexOf("10.0.0.1") + 8, "IP_ADDRESS");
        GoldSpan second = new GoldSpan(text.lastIndexOf("10.0.0.1"), text.lastIndexOf("10.0.0.1") + 8, "IP_ADDRESS");
        GoldDoc d = doc(text, List.of(first, second));
        // only one prediction -> 1 TP, 1 FN
        Map<String, List<Prediction>> preds = Map.of("doc-1",
            List.of(new Prediction("IP_ADDRESS", "10.0.0.1")));

        ScoreResult r = ValueScorer.score(List.of(d), preds);

        assertThat(r.strictOverall().tp()).isEqualTo(1);
        assertThat(r.strictOverall().fn()).isEqualTo(1);
    }

    @Test
    void Should_ComputeF1_When_MixedOutcomes() {
        String text = "iban CH9300762011623852957 card 4111111111111111 pw s3cret";
        GoldDoc d = doc(text, List.of(
            goldOf(text, "CH9300762011623852957", "IBAN"),
            goldOf(text, "4111111111111111", "CARD_NUMBER"),
            goldOf(text, "s3cret", "PASSWORD")));
        Map<String, List<Prediction>> preds = Map.of("doc-1", List.of(
            new Prediction("IBAN", "CH9300762011623852957"),   // TP
            new Prediction("CARD_NUMBER", "4111111111111111"), // TP
            new Prediction("API_KEY", "abc")                   // FP ; PASSWORD gold -> FN
        ));

        ScoreResult r = ValueScorer.score(List.of(d), preds);

        assertThat(r.strictOverall().tp()).isEqualTo(2);
        assertThat(r.strictOverall().fp()).isEqualTo(1);
        assertThat(r.strictOverall().fn()).isEqualTo(1);
        assertThat(r.strictOverall().f1()).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    void Should_ResolveCanonicalAndIgnoreAndUnknown_When_UsingExtractorConceptMap() {
        ExtractorConceptMap cm = ExtractorConceptMap.of(Map.of(
            "_default", Map.of("iban", "IBAN", "email", "IGNORE"),
            "modelx", Map.of("iban", "CRYPTO")));

        // per-model override beats _default
        assertThat(cm.canonical("modelx", "iban")).isEqualTo("CRYPTO");
        // _default applies to other models
        assertThat(cm.canonical("other", "IBAN")).isEqualTo("IBAN");
        // IGNORE -> null (dropped)
        assertThat(cm.canonical("other", "email")).isNull();
        // unknown -> null AND recorded
        assertThat(cm.canonical("other", "weird_label")).isNull();
        assertThat(cm.unknownLabels()).contains("weird_label");
    }
}
