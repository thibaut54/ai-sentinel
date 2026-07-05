package pro.softcom.aisentinel.domain.pii.reporting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RevealedSecret")
class RevealedSecretTest {

    @Test
    @DisplayName("Should_CreateValidSecret_When_AllFieldsValid")
    void Should_CreateValidSecret_When_AllFieldsValid() {
        // Act
        RevealedSecret secret = new RevealedSecret(5, 15, "secret123", "context here", "masked context");

        // Assert
        assertThat(secret.startPosition()).isEqualTo(5);
        assertThat(secret.endPosition()).isEqualTo(15);
        assertThat(secret.sensitiveValue()).isEqualTo("secret123");
    }

    @Test
    @DisplayName("Should_ThrowException_When_StartPositionIsNegative")
    void Should_ThrowException_When_StartPositionIsNegative() {
        assertThatThrownBy(() -> new RevealedSecret(-1, 5, "value", "ctx", "masked"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startPosition");
    }

    @Test
    @DisplayName("Should_ThrowException_When_EndPositionIsBeforeStart")
    void Should_ThrowException_When_EndPositionIsBeforeStart() {
        assertThatThrownBy(() -> new RevealedSecret(10, 5, "value", "ctx", "masked"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endPosition");
    }

    @Test
    @DisplayName("Should_AllowEqualStartAndEnd_When_ZeroLengthRange")
    void Should_AllowEqualStartAndEnd_When_ZeroLengthRange() {
        // Zero-length range is allowed
        RevealedSecret secret = new RevealedSecret(5, 5, "", "ctx", "masked");
        assertThat(secret.startPosition()).isEqualTo(secret.endPosition());
    }

    @Test
    @DisplayName("Should_AllowZeroStart_When_PiiAtBeginning")
    void Should_AllowZeroStart_When_PiiAtBeginning() {
        RevealedSecret secret = new RevealedSecret(0, 10, "value", "ctx", "masked");
        assertThat(secret.startPosition()).isZero();
    }
}
