package pro.softcom.aisentinel.domain.pii.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.reporting.AccessPurpose;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PiiAuditRecord")
class PiiAuditRecordTest {

    private static final Instant NOW = Instant.parse("2024-01-01T10:00:00Z");
    private static final Instant RETENTION = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    @DisplayName("Should_CreateRecord_When_AllFieldsAreValid")
    void Should_CreateRecord_When_AllFieldsAreValid() {
        PiiAuditRecord record = new PiiAuditRecord(
                "scan-1", "SPACE", "page-1", "Page Title",
                NOW, RETENTION, AccessPurpose.USER_DISPLAY, 3);

        assertThat(record.scanId()).isEqualTo("scan-1");
        assertThat(record.spaceKey()).isEqualTo("SPACE");
        assertThat(record.pageId()).isEqualTo("page-1");
        assertThat(record.piiEntitiesCount()).isEqualTo(3);
        assertThat(record.purpose()).isEqualTo(AccessPurpose.USER_DISPLAY);
    }

    @Test
    @DisplayName("Should_ThrowException_When_ScanIdIsNull")
    void Should_ThrowException_When_ScanIdIsNull() {
        assertThatThrownBy(() -> new PiiAuditRecord(
                null, "SPACE", "page-1", "Title",
                NOW, RETENTION, AccessPurpose.USER_DISPLAY, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanId");
    }

    @Test
    @DisplayName("Should_ThrowException_When_ScanIdIsBlank")
    void Should_ThrowException_When_ScanIdIsBlank() {
        assertThatThrownBy(() -> new PiiAuditRecord(
                "  ", "SPACE", "page-1", "Title",
                NOW, RETENTION, AccessPurpose.USER_DISPLAY, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanId");
    }

    @Test
    @DisplayName("Should_ThrowException_When_AccessedAtIsNull")
    void Should_ThrowException_When_AccessedAtIsNull() {
        assertThatThrownBy(() -> new PiiAuditRecord(
                "scan-1", "SPACE", "page-1", "Title",
                null, RETENTION, AccessPurpose.USER_DISPLAY, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accessedAt");
    }

    @Test
    @DisplayName("Should_ThrowException_When_RetentionUntilIsNull")
    void Should_ThrowException_When_RetentionUntilIsNull() {
        assertThatThrownBy(() -> new PiiAuditRecord(
                "scan-1", "SPACE", "page-1", "Title",
                NOW, null, AccessPurpose.USER_DISPLAY, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retentionUntil");
    }

    @Test
    @DisplayName("Should_ThrowException_When_PurposeIsNull")
    void Should_ThrowException_When_PurposeIsNull() {
        assertThatThrownBy(() -> new PiiAuditRecord(
                "scan-1", "SPACE", "page-1", "Title",
                NOW, RETENTION, null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purpose");
    }

    @Test
    @DisplayName("Should_ThrowException_When_PiiEntitiesCountIsNegative")
    void Should_ThrowException_When_PiiEntitiesCountIsNegative() {
        assertThatThrownBy(() -> new PiiAuditRecord(
                "scan-1", "SPACE", "page-1", "Title",
                NOW, RETENTION, AccessPurpose.USER_DISPLAY, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("piiEntitiesCount");
    }

    @Test
    @DisplayName("Should_AllowZeroPiiEntities_When_PageHasNoDetections")
    void Should_AllowZeroPiiEntities_When_PageHasNoDetections() {
        PiiAuditRecord record = new PiiAuditRecord(
                "scan-1", "SPACE", "page-1", null,
                NOW, RETENTION, AccessPurpose.USER_DISPLAY, 0);
        assertThat(record.piiEntitiesCount()).isZero();
    }
}
