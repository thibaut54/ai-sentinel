package pro.softcom.aisentinel.domain.pii.scan.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DatabaseSourceType} enum.
 * Validates enum completeness and valueOf behavior.
 */
class DatabaseSourceTypeTest {

    @Test
    void Should_HaveExactlyFourValues_When_CheckingEnumValues() {
        // Assert
        assertThat(DatabaseSourceType.values()).hasSize(4);
    }

    @Test
    void Should_ContainExpectedValues_When_CheckingEnumEntries() {
        // Assert
        assertThat(DatabaseSourceType.values())
            .containsExactlyInAnyOrder(
                DatabaseSourceType.POSTGRES,
                DatabaseSourceType.ORACLE,
                DatabaseSourceType.MONGO,
                DatabaseSourceType.ELASTIC
            );
    }

    @Test
    void Should_ReturnCorrectEnum_When_ValueOfCalledWithValidName() {
        // Assert
        assertThat(DatabaseSourceType.valueOf("POSTGRES")).isEqualTo(DatabaseSourceType.POSTGRES);
        assertThat(DatabaseSourceType.valueOf("ORACLE")).isEqualTo(DatabaseSourceType.ORACLE);
        assertThat(DatabaseSourceType.valueOf("MONGO")).isEqualTo(DatabaseSourceType.MONGO);
        assertThat(DatabaseSourceType.valueOf("ELASTIC")).isEqualTo(DatabaseSourceType.ELASTIC);
    }

    @Test
    void Should_ThrowException_When_ValueOfCalledWithInvalidName() {
        assertThatThrownBy(() -> DatabaseSourceType.valueOf("MYSQL"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
