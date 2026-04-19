package pro.softcom.aisentinel.domain.pii.detection;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link GdprDataClassification} domain enum.
 * <p>
 * Verifies presence of the 4 legal values plus the presentation metadata
 * (article, localized label, color hex, PrimeNG badge severity).
 */
class GdprDataClassificationTest {

    @Test
    @DisplayName("Should_DefineFourValues_When_EnumIsDeclared")
    void Should_DefineFourValues_When_EnumIsDeclared() {
        assertThat(GdprDataClassification.values())
                .as("GDPR enum must expose exactly 4 legal categories")
                .hasSize(4)
                .containsExactly(
                        GdprDataClassification.SPECIAL_CATEGORY,
                        GdprDataClassification.CRIMINAL_DATA,
                        GdprDataClassification.PERSONAL_DATA_HIGH_RISK,
                        GdprDataClassification.PERSONAL_DATA
                );
    }

    @Test
    @DisplayName("Should_ExposeArt9Metadata_When_QueryingSpecialCategory")
    void Should_ExposeArt9Metadata_When_QueryingSpecialCategory() {
        GdprDataClassification value = GdprDataClassification.SPECIAL_CATEGORY;

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(value.getArticle()).isEqualTo("Art. 9");
        softly.assertThat(value.getLabelFr()).isEqualTo("Categorie speciale");
        softly.assertThat(value.getColorHex()).isEqualTo("#DC2626");
        softly.assertThat(value.getBadgeSeverity()).isEqualTo("red");
        softly.assertAll();
    }

    @Test
    @DisplayName("Should_ExposeArt10Metadata_When_QueryingCriminalData")
    void Should_ExposeArt10Metadata_When_QueryingCriminalData() {
        GdprDataClassification value = GdprDataClassification.CRIMINAL_DATA;

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(value.getArticle()).isEqualTo("Art. 10");
        softly.assertThat(value.getLabelFr()).isEqualTo("Donnees penales");
        softly.assertThat(value.getColorHex()).isEqualTo("#EA580C");
        softly.assertThat(value.getBadgeSeverity()).isEqualTo("orange");
        softly.assertAll();
    }

    @Test
    @DisplayName("Should_ExposeArt6Metadata_When_QueryingHighRisk")
    void Should_ExposeArt6Metadata_When_QueryingHighRisk() {
        GdprDataClassification value = GdprDataClassification.PERSONAL_DATA_HIGH_RISK;

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(value.getArticle()).isEqualTo("Art. 6");
        softly.assertThat(value.getLabelFr()).isEqualTo("Donnees personnelles haut risque");
        softly.assertThat(value.getColorHex()).isEqualTo("#CA8A04");
        softly.assertThat(value.getBadgeSeverity()).isEqualTo("yellow");
        softly.assertAll();
    }

    @Test
    @DisplayName("Should_ExposeArt6Metadata_When_QueryingOrdinaryPersonalData")
    void Should_ExposeArt6Metadata_When_QueryingOrdinaryPersonalData() {
        GdprDataClassification value = GdprDataClassification.PERSONAL_DATA;

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(value.getArticle()).isEqualTo("Art. 6");
        softly.assertThat(value.getLabelFr()).isEqualTo("Donnees personnelles");
        softly.assertThat(value.getColorHex()).isEqualTo("#16A34A");
        softly.assertThat(value.getBadgeSeverity()).isEqualTo("green");
        softly.assertAll();
    }

    @Test
    @DisplayName("Should_ReturnEnumFromName_When_CallingValueOf")
    void Should_ReturnEnumFromName_When_CallingValueOf() {
        assertThat(GdprDataClassification.valueOf("SPECIAL_CATEGORY"))
                .isEqualTo(GdprDataClassification.SPECIAL_CATEGORY);
    }
}
