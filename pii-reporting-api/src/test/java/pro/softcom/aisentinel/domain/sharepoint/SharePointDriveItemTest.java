package pro.softcom.aisentinel.domain.sharepoint;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SharePointDriveItem} domain record.
 */
class SharePointDriveItemTest {

    @Test
    void Should_CreateFileItem_When_ValidDataProvided() {
        // Given
        var lastModified = Instant.parse("2026-01-15T10:30:00Z");

        // When
        var item = new SharePointDriveItem(
            "item-456", "report.pdf", "https://example.sharepoint.com/report.pdf",
            "drive-789", "application/pdf", 1024L, lastModified, false
        );

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(item.id()).isEqualTo("item-456");
        softly.assertThat(item.name()).isEqualTo("report.pdf");
        softly.assertThat(item.webUrl()).isEqualTo("https://example.sharepoint.com/report.pdf");
        softly.assertThat(item.driveId()).isEqualTo("drive-789");
        softly.assertThat(item.mimeType()).isEqualTo("application/pdf");
        softly.assertThat(item.size()).isEqualTo(1024L);
        softly.assertThat(item.lastModified()).isEqualTo(lastModified);
        softly.assertThat(item.isFolder()).isFalse();
        softly.assertAll();
    }

    @Test
    void Should_CreateFolderItem_When_IsFolderTrue() {
        // When
        var folder = new SharePointDriveItem(
            "folder-123", "Documents", "https://example.sharepoint.com/Documents",
            "drive-789", null, null, null, true
        );

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(folder.id()).isEqualTo("folder-123");
        softly.assertThat(folder.name()).isEqualTo("Documents");
        softly.assertThat(folder.mimeType()).isNull();
        softly.assertThat(folder.size()).isNull();
        softly.assertThat(folder.lastModified()).isNull();
        softly.assertThat(folder.isFolder()).isTrue();
        softly.assertAll();
    }

    @Test
    void Should_ThrowException_When_IdIsNull() {
        assertThatThrownBy(() -> new SharePointDriveItem(
            null, "file.txt", "url", "drive", "text/plain", 100L, Instant.now(), false
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DriveItem id cannot be empty");
    }

    @Test
    void Should_ThrowException_When_IdIsBlank() {
        assertThatThrownBy(() -> new SharePointDriveItem(
            "  ", "file.txt", "url", "drive", "text/plain", 100L, Instant.now(), false
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DriveItem id cannot be empty");
    }

    @Test
    void Should_ThrowException_When_NameIsNull() {
        assertThatThrownBy(() -> new SharePointDriveItem(
            "item-1", null, "url", "drive", "text/plain", 100L, Instant.now(), false
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DriveItem name cannot be empty");
    }

    @Test
    void Should_ThrowException_When_NameIsBlank() {
        assertThatThrownBy(() -> new SharePointDriveItem(
            "item-1", "  ", "url", "drive", "text/plain", 100L, Instant.now(), false
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DriveItem name cannot be empty");
    }
}
