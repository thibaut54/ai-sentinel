package pro.softcom.aisentinel.integration.bench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.JudgeStatus;

/**
 * Unit tests for the pure span-level scorer. No Docker / Spring — this is the
 * benchmark's verifiable correctness core.
 */
class SpanScorerTest {

    private static final String TEXT =
        "IBAN CH9300762011623852957 card 4111111111111111 mail a@b.io end";
    //   0123456789...                                              indices below are illustrative

    private static Finding finding(int start, int end, String canonical) {
        return new Finding(start, end, canonical, DetectorSource.GLINER2, 0.9, JudgeStatus.NOT_AUDITED);
    }

    private static GoldDoc doc(List<GoldSpan> spans, List<GoldSpan> ignore) {
        return new GoldDoc("doc-1", "gretelai", "English", TEXT, spans, ignore);
    }

    @Test
    void Should_CountExactMatchAsTruePositive_When_SpanAndLabelMatch() {
        GoldDoc d = doc(List.of(new GoldSpan(5, 26, "IBAN")), List.of());
        Map<String, List<Finding>> findings = Map.of("doc-1", List.of(finding(5, 26, "IBAN")));

        ScoreResult r = SpanScorer.score(List.of(d), findings);

        assertThat(r.strictOverall().tp()).isEqualTo(1);
        assertThat(r.strictOverall().fp()).isZero();
        assertThat(r.strictOverall().fn()).isZero();
        assertThat(r.strictOverall().precision()).isEqualTo(1.0);
        assertThat(r.strictOverall().recall()).isEqualTo(1.0);
        assertThat(r.strictOverall().f1()).isEqualTo(1.0);
    }

    @Test
    void Should_CountAsFalseNegative_When_NoFindingMatchesGoldSpan() {
        GoldDoc d = doc(List.of(new GoldSpan(5, 26, "IBAN")), List.of());

        ScoreResult r = SpanScorer.score(List.of(d), Map.of());

        assertThat(r.strictOverall().tp()).isZero();
        assertThat(r.strictOverall().fn()).isEqualTo(1);
        assertThat(r.strictOverall().recall()).isZero();
        assertThat(r.falseNegatives()).hasSize(1);
        assertThat(r.falseNegatives().get(0).goldLabel()).isEqualTo("IBAN");
    }

    @Test
    void Should_CountAsFalsePositive_When_FindingMatchesNoGoldAndNoIgnoreZone() {
        GoldDoc d = doc(List.of(), List.of());
        Map<String, List<Finding>> findings = Map.of("doc-1", List.of(finding(5, 26, "IBAN")));

        ScoreResult r = SpanScorer.score(List.of(d), findings);

        assertThat(r.strictOverall().fp()).isEqualTo(1);
        assertThat(r.strictOverall().precision()).isZero();
        assertThat(r.falsePositives()).hasSize(1);
        assertThat(r.falsePositives().get(0).findingLabel()).isEqualTo("IBAN");
        assertThat(r.falsePositives().get(0).source()).isEqualTo(DetectorSource.GLINER2);
    }

    @Test
    void Should_NotPenalizeFinding_When_OverlappingAnIgnoreZone() {
        // dataset marked [49,55) as email (out-of-scope) -> ignore zone
        GoldDoc d = doc(List.of(), List.of(new GoldSpan(49, 55, "email")));
        Map<String, List<Finding>> findings = Map.of("doc-1", List.of(finding(49, 55, "IBAN")));

        ScoreResult r = SpanScorer.score(List.of(d), findings);

        assertThat(r.strictOverall().tp()).isZero();
        assertThat(r.strictOverall().fp()).isZero();
        assertThat(r.strictOverall().fn()).isZero();
        assertThat(r.typeAgnosticOverall().fp()).isZero();
    }

    @Test
    void Should_CountAsTypingError_When_SpanMatchesButLabelDiffers() {
        // right boundaries, wrong canonical label
        GoldDoc d = doc(List.of(new GoldSpan(5, 26, "IBAN")), List.of());
        Map<String, List<Finding>> findings = Map.of("doc-1", List.of(finding(5, 26, "BANK_ACCOUNT")));

        ScoreResult r = SpanScorer.score(List.of(d), findings);

        // strict: gold IBAN is missed (FN), finding BANK_ACCOUNT is wrong (FP)
        assertThat(r.strictOverall().tp()).isZero();
        assertThat(r.strictOverall().fn()).isEqualTo(1);
        assertThat(r.strictOverall().fp()).isEqualTo(1);
        // type-agnostic: the span is localised correctly -> TP, no FP/FN
        assertThat(r.typeAgnosticOverall().tp()).isEqualTo(1);
        assertThat(r.typeAgnosticOverall().fp()).isZero();
        assertThat(r.typeAgnosticOverall().fn()).isZero();
        // the gap isolates the typing error
        assertThat(r.typingErrors()).isEqualTo(1);
    }

    @Test
    void Should_CountSurplusFindingOnMatchedSpanAsFalsePositive_When_OverDetecting() {
        GoldDoc d = doc(List.of(new GoldSpan(5, 26, "IBAN")), List.of());
        Map<String, List<Finding>> findings = Map.of("doc-1",
            List.of(finding(5, 26, "IBAN"), finding(5, 26, "IBAN")));

        ScoreResult r = SpanScorer.score(List.of(d), findings);

        assertThat(r.strictOverall().tp()).isEqualTo(1);
        assertThat(r.strictOverall().fp()).isEqualTo(1);
    }

    @Test
    void Should_AggregatePerLabelAndPerCategory_When_MultipleConcepts() {
        GoldDoc d = doc(
            List.of(new GoldSpan(5, 26, "IBAN"), new GoldSpan(32, 48, "CARD_NUMBER")),
            List.of());
        Map<String, List<Finding>> findings = Map.of("doc-1",
            List.of(finding(5, 26, "IBAN"), finding(32, 48, "CARD_NUMBER")));

        ScoreResult r = SpanScorer.score(List.of(d), findings);

        assertThat(r.strictByLabel()).containsKeys("IBAN", "CARD_NUMBER");
        assertThat(r.strictByLabel().get("IBAN").tp()).isEqualTo(1);
        assertThat(r.strictByLabel().get("CARD_NUMBER").tp()).isEqualTo(1);
        // both IBAN and CARD_NUMBER are BANKING_PAYMENT
        assertThat(r.strictByCategory()).containsKey(CanonicalConcepts.BANKING_PAYMENT);
        assertThat(r.strictByCategory().get(CanonicalConcepts.BANKING_PAYMENT).tp()).isEqualTo(2);
    }

    @Test
    void Should_ComputePrecisionRecallF1_When_MixedOutcomes() {
        // 2 TP, 1 FP, 1 FN -> P = 2/3, R = 2/3, F1 = 2/3
        GoldDoc d = doc(
            List.of(new GoldSpan(0, 4, "IBAN"), new GoldSpan(5, 26, "IBAN"), new GoldSpan(32, 48, "CARD_NUMBER")),
            List.of());
        Map<String, List<Finding>> findings = Map.of("doc-1", List.of(
            finding(0, 4, "IBAN"),       // TP
            finding(5, 26, "IBAN"),      // TP
            finding(60, 63, "PASSWORD")  // FP (no gold, no ignore)
            // CARD_NUMBER gold unmatched -> FN
        ));

        ScoreResult r = SpanScorer.score(List.of(d), findings);

        assertThat(r.strictOverall().tp()).isEqualTo(2);
        assertThat(r.strictOverall().fp()).isEqualTo(1);
        assertThat(r.strictOverall().fn()).isEqualTo(1);
        assertThat(r.strictOverall().precision()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(r.strictOverall().recall()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(r.strictOverall().f1()).isCloseTo(2.0 / 3.0, within(1e-9));
    }

    @Test
    void Should_ReturnZeroNotNaN_When_NoPredictionsAndNoGold() {
        ScoreResult r = SpanScorer.score(List.of(doc(List.of(), List.of())), Map.of());

        assertThat(r.strictOverall().precision()).isZero();
        assertThat(r.strictOverall().recall()).isZero();
        assertThat(r.strictOverall().f1()).isZero();
    }

    @Test
    void Should_MapEmittedTypeToCanonical_When_UsingConceptMap() {
        ConceptMap cm = ConceptMap.of(Map.of(
            DetectorSource.PRESIDIO, Map.of("IBAN_CODE", "IBAN"),
            DetectorSource.OPENMED, Map.of("CVV", "CARD_CVV")));

        assertThat(cm.canonical(DetectorSource.PRESIDIO, "IBAN_CODE")).isEqualTo("IBAN");
        assertThat(cm.canonical(DetectorSource.OPENMED, "CVV")).isEqualTo("CARD_CVV");
        // normalisation: spaces/dashes/case folded to the map key form
        assertThat(cm.canonical(DetectorSource.PRESIDIO, "iban-code")).isEqualTo("IBAN");
        // unmapped type -> own normalised name (fallback, never null)
        assertThat(cm.canonical(DetectorSource.PRESIDIO, "NRP")).isEqualTo("NRP");
        assertThat(cm.canonical(DetectorSource.REGEX, "anything new")).isEqualTo("ANYTHING_NEW");
    }
}
