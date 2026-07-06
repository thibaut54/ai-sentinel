package pro.softcom.aisentinel.domain.pii.remediation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.application.pii.reporting.service.PiiMaskingUtils;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("RedactionToken")
class RedactionTokenTest {

    @Test
    @DisplayName("Should_WrapTypeInBrackets_When_TypeIsProvided")
    void Should_WrapTypeInBrackets_When_TypeIsProvided() {
        assertThat(RedactionToken.forType("EMAIL_ADDRESS")).isEqualTo("[EMAIL_ADDRESS]");
    }

    @Test
    @DisplayName("Should_ReturnUnknownToken_When_TypeIsNull")
    void Should_ReturnUnknownToken_When_TypeIsNull() {
        assertThat(RedactionToken.forType(null)).isEqualTo("[UNKNOWN]");
    }

    @Test
    @DisplayName("Should_ReturnUnknownToken_When_TypeIsBlank")
    void Should_ReturnUnknownToken_When_TypeIsBlank() {
        assertThat(RedactionToken.forType("  ")).isEqualTo("[UNKNOWN]");
    }

    @Test
    @DisplayName("Should_ReturnUnknownToken_When_TypeIsLiteralNullString")
    void Should_ReturnUnknownToken_When_TypeIsLiteralNullString() {
        assertThat(RedactionToken.forType("null")).isEqualTo("[UNKNOWN]");
    }

    @Test
    @DisplayName("Should_MatchPiiMaskingUtilsConvention_When_ComparedOnSameInputs")
    void Should_MatchPiiMaskingUtilsConvention_When_ComparedOnSameInputs() {
        assertSoftly(softly ->
                Arrays.asList("EMAIL_ADDRESS", "PHONE_NUMBER", null, "", "  ", "null").forEach(type ->
                        softly.assertThat(RedactionToken.forType(type))
                                .as("type=%s", type)
                                .isEqualTo(PiiMaskingUtils.token(type))));
    }
}
