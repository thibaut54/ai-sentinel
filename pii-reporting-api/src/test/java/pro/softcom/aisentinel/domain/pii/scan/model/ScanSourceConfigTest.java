package pro.softcom.aisentinel.domain.pii.scan.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Unit tests for {@link ScanSourceConfig} domain record.
 * Validates compact constructor validation and immutability.
 */
class ScanSourceConfigTest {

    @Test
    void Should_CreateConfig_When_AllFieldsAreValid() {
        // Arrange
        Map<String, String> properties = Map.of("url", "jdbc:postgresql://localhost/db", "table", "users");

        // Act
        ScanSourceConfig config = new ScanSourceConfig(DatabaseSourceType.POSTGRES, properties);

        // Assert
        assertSoftly(softly -> {
            softly.assertThat(config.type()).isEqualTo(DatabaseSourceType.POSTGRES);
            softly.assertThat(config.properties()).hasSize(2);
            softly.assertThat(config.properties().get("url")).isEqualTo("jdbc:postgresql://localhost/db");
            softly.assertThat(config.properties().get("table")).isEqualTo("users");
        });
    }

    @Test
    void Should_CreateConfigWithEmptyProperties_When_PropertiesIsNull() {
        // Arrange & Act
        ScanSourceConfig config = new ScanSourceConfig(DatabaseSourceType.POSTGRES, null);

        // Assert
        assertThat(config.properties()).isEmpty();
    }

    @Test
    void Should_ThrowIllegalArgumentException_When_TypeIsNull() {
        final Map<String, String> properties = Map.of();
        assertThatThrownBy(() -> new ScanSourceConfig(null, properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SourceType cannot be null");
    }

    @Test
    void Should_SupportAllDatabaseSourceTypes_When_CreatingConfig() {
        for (DatabaseSourceType type : DatabaseSourceType.values()) {
            ScanSourceConfig config = new ScanSourceConfig(type, Map.of());
            assertThat(config.type()).isEqualTo(type);
        }
    }
}
