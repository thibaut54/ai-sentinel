package pro.softcom.aisentinel.domain.pii.export;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SourceType} enum.
 */
class SourceTypeTest {

    // --- getValue ---

    @Test
    void Should_ReturnCorrectValue_When_GetValueCalledOnEachEnum() {
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(SourceType.CONFLUENCE.getValue()).isEqualTo("CONFLUENCE");
        softly.assertThat(SourceType.JIRA.getValue()).isEqualTo("JIRA");
        softly.assertThat(SourceType.SHAREPOINT.getValue()).isEqualTo("SHAREPOINT");
        softly.assertAll();
    }

    // --- fromValue: nominal and case-insensitive cases ---

    @ParameterizedTest
    @CsvSource({
        "CONFLUENCE, CONFLUENCE",
        "JIRA, JIRA",
        "SHAREPOINT, SHAREPOINT",
        "confluence, CONFLUENCE",
        "Jira, JIRA",
        "sharePoint, SHAREPOINT",
        "jIrA, JIRA"
    })
    void Should_ReturnCorrectEnum_When_FromValueCalledWithAnyCase(String input, String expectedName) {
        SourceType result = SourceType.fromValue(input);
        assertThat(result.name()).isEqualTo(expectedName);
    }

    // --- fromValue: error cases ---

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "database", "   ", "CONFLUENCEX", "jira "})
    void Should_ThrowIllegalArgumentException_When_FromValueCalledWithInvalidValue(String input) {
        assertThatThrownBy(() -> SourceType.fromValue(input))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown source type: " + input);
    }

    @Test
    void Should_ThrowIllegalArgumentException_When_FromValueCalledWithNull() {
        assertThatThrownBy(() -> SourceType.fromValue(null))
            .isInstanceOf(Exception.class);
    }

    // --- enum completeness ---

    @Test
    void Should_HaveExactlyThreeValues_When_CheckingEnumValues() {
        assertThat(SourceType.values()).hasSize(3);
    }

    @Test
    void Should_ReturnCorrectEnum_When_ValueOfCalledWithEnumName() {
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(SourceType.valueOf("CONFLUENCE")).isEqualTo(SourceType.CONFLUENCE);
        softly.assertThat(SourceType.valueOf("JIRA")).isEqualTo(SourceType.JIRA);
        softly.assertThat(SourceType.valueOf("SHAREPOINT")).isEqualTo(SourceType.SHAREPOINT);
        softly.assertAll();
    }
}
