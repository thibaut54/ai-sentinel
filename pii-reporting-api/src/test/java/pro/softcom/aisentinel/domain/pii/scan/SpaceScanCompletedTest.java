package pro.softcom.aisentinel.domain.pii.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.softcom.aisentinel.domain.pii.export.SourceType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Unit tests for {@link SpaceScanCompleted} domain event.
 * Validates compact constructor validation rules.
 */
class SpaceScanCompletedTest {

    @Test
    void Should_CreateEvent_When_AllFieldsAreValid() {
        // Arrange & Act
        SpaceScanCompleted event = new SpaceScanCompleted("scan-123", "SPACE-A", SourceType.CONFLUENCE);

        // Assert
        assertSoftly(softly -> {
            softly.assertThat(event.scanId()).isEqualTo("scan-123");
            softly.assertThat(event.sourceKey()).isEqualTo("SPACE-A");
            softly.assertThat(event.sourceType()).isEqualTo(SourceType.CONFLUENCE);
        });
    }

    @Test
    void Should_CreateEvent_When_SourceTypeIsSharePoint() {
        // Arrange & Act
        SpaceScanCompleted event = new SpaceScanCompleted("scan-456", "site-id-1", SourceType.SHAREPOINT);

        // Assert
        assertSoftly(softly -> {
            softly.assertThat(event.scanId()).isEqualTo("scan-456");
            softly.assertThat(event.sourceKey()).isEqualTo("site-id-1");
            softly.assertThat(event.sourceType()).isEqualTo(SourceType.SHAREPOINT);
        });
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    void Should_ThrowIllegalArgumentException_When_ScanIdIsBlank(String scanId) {
        assertThatThrownBy(() -> new SpaceScanCompleted(scanId, "SPACE-A", SourceType.CONFLUENCE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scanId");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    void Should_ThrowIllegalArgumentException_When_SourceKeyIsBlank(String sourceKey) {
        assertThatThrownBy(() -> new SpaceScanCompleted("scan-123", sourceKey, SourceType.CONFLUENCE))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceKey");
    }

    @Test
    void Should_ThrowIllegalArgumentException_When_SourceTypeIsNull() {
        assertThatThrownBy(() -> new SpaceScanCompleted("scan-123", "SPACE-A", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("sourceType");
    }

    @Test
    void Should_SupportAllSourceTypes_When_CreatingEvent() {
        for (SourceType sourceType : SourceType.values()) {
            SpaceScanCompleted event = new SpaceScanCompleted("scan-1", "source-1", sourceType);
            assertThat(event.sourceType()).isEqualTo(sourceType);
        }
    }
}
