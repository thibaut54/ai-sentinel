package pro.softcom.aisentinel.domain.pii.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SourceType")
class SourceTypeTest {

    @ParameterizedTest
    @ValueSource(strings = {"CONFLUENCE", "confluence", "Confluence"})
    @DisplayName("Should_ParseConfluence_When_ValidStringCaseInsensitive")
    void Should_ParseConfluence_When_ValidStringCaseInsensitive(String value) {
        // Act
        SourceType result = SourceType.fromValue(value);

        // Assert
        assertThat(result).isEqualTo(SourceType.CONFLUENCE);
    }

    @Test
    @DisplayName("Should_ReturnCorrectValue_When_GetValueCalled")
    void Should_ReturnCorrectValue_When_GetValueCalled() {
        assertThat(SourceType.CONFLUENCE.getValue()).isEqualTo("CONFLUENCE");
    }

    @Test
    @DisplayName("Should_ThrowException_When_UnknownSourceType")
    void Should_ThrowException_When_UnknownSourceType() {
        assertThatThrownBy(() -> SourceType.fromValue("JIRA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown source type");
    }

    @Test
    @DisplayName("Should_ThrowException_When_NullValue")
    void Should_ThrowException_When_NullValue() {
        assertThatThrownBy(() -> SourceType.fromValue(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown source type");
    }
}
