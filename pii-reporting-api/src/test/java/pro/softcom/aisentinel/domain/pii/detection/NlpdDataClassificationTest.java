package pro.softcom.aisentinel.domain.pii.detection;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link NlpdDataClassification} domain enum.
 * <p>
 * Verifies the 4 legal values and their metadata mirroring the nLPD articles
 * (Art. 5 let. a, c, g and Art. 8).
 */
class NlpdDataClassificationTest {

    @Test
    @DisplayName("Should_DefineFourValues_When_EnumIsDeclared")
    void Should_DefineFourValues_When_EnumIsDeclared() {
        assertThat(NlpdDataClassification.values())
                .as("nLPD enum must expose exactly 4 legal categories")
                .hasSize(4)
                .containsExactly(
                        NlpdDataClassification.SENSITIVE_DATA,
                        NlpdDataClassification.HIGH_RISK_PROFILING_DATA,
                        NlpdDataClassification.PERSONAL_DATA_HIGH_RISK,
                        NlpdDataClassification.PERSONAL_DATA
                );
    }

    @Test
    @DisplayName("Should_ExposeArt5letCMetadata_When_QueryingSensitiveData")
    void Should_ExposeArt5letCMetadata_When_QueryingSensitiveData() {
        NlpdDataClassification value = NlpdDataClassification.SENSITIVE_DATA;

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(value.getArticle()).isEqualTo("Art. 5 let. c");
        softly.assertThat(value.getLabelFr()).isEqualTo("Donnees sensibles");
        softly.assertThat(value.getColorHex()).isEqualTo("#DC2626");
        softly.assertThat(value.getBadgeSeverity()).isEqualTo("red");
        softly.assertAll();
    }

    @Test
    @DisplayName("Should_ExposeArt5letGMetadata_When_QueryingHighRiskProfiling")
    void Should_ExposeArt5letGMetadata_When_QueryingHighRiskProfiling() {
        NlpdDataClassification value = NlpdDataClassification.HIGH_RISK_PROFILING_DATA;

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(value.getArticle()).isEqualTo("Art. 5 let. g");
        softly.assertThat(value.getLabelFr()).isEqualTo("Profilage a risque eleve");
        softly.assertThat(value.getColorHex()).isEqualTo("#EA580C");
        softly.assertThat(value.getBadgeSeverity()).isEqualTo("orange");
        softly.assertAll();
    }

    @Test
    @DisplayName("Should_ExposeArt8Metadata_When_QueryingHighRiskPersonalData")
    void Should_ExposeArt8Metadata_When_QueryingHighRiskPersonalData() {
        NlpdDataClassification value = NlpdDataClassification.PERSONAL_DATA_HIGH_RISK;

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(value.getArticle()).isEqualTo("Art. 8");
        softly.assertThat(value.getLabelFr()).isEqualTo("Donnees personnelles haut risque");
        softly.assertThat(value.getColorHex()).isEqualTo("#CA8A04");
        softly.assertThat(value.getBadgeSeverity()).isEqualTo("yellow");
        softly.assertAll();
    }

    @Test
    @DisplayName("Should_ExposeArt5letAMetadata_When_QueryingOrdinaryPersonalData")
    void Should_ExposeArt5letAMetadata_When_QueryingOrdinaryPersonalData() {
        NlpdDataClassification value = NlpdDataClassification.PERSONAL_DATA;

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(value.getArticle()).isEqualTo("Art. 5 let. a");
        softly.assertThat(value.getLabelFr()).isEqualTo("Donnees personnelles");
        softly.assertThat(value.getColorHex()).isEqualTo("#16A34A");
        softly.assertThat(value.getBadgeSeverity()).isEqualTo("green");
        softly.assertAll();
    }

    @Test
    @DisplayName("Should_ReturnEnumFromName_When_CallingValueOf")
    void Should_ReturnEnumFromName_When_CallingValueOf() {
        assertThat(NlpdDataClassification.valueOf("SENSITIVE_DATA"))
                .isEqualTo(NlpdDataClassification.SENSITIVE_DATA);
    }
}
