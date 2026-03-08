package pro.softcom.aisentinel.domain.sharepoint;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SharePointScannableFile} domain record.
 */
class SharePointScannableFileTest {

    private static final String SITE_ID = "site-abc-123";
    private static final String ITEM_ID = "item-456";
    private static final String FILE_NAME = "report.pdf";
    private static final String WEB_URL = "https://example.sharepoint.com/report.pdf";
    private static final String DRIVE_ID = "drive-789";
    private static final String MIME_TYPE = "application/pdf";
    private static final String EXTRACTED_TEXT = "This document contains personal information: John Doe, john.doe@example.com";

    private SharePointDriveItem createDriveItem() {
        return new SharePointDriveItem(
            ITEM_ID, FILE_NAME, WEB_URL, DRIVE_ID, MIME_TYPE, 2048L,
            Instant.parse("2026-03-01T12:00:00Z"), false
        );
    }

    @Test
    void Should_CreateScannableFile_When_ValidDataProvided() {
        // Arrange
        var driveItem = createDriveItem();

        // Act
        var scannableFile = new SharePointScannableFile(SITE_ID, driveItem, EXTRACTED_TEXT);

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(scannableFile.siteId()).isEqualTo(SITE_ID);
        softly.assertThat(scannableFile.driveItem()).isEqualTo(driveItem);
        softly.assertThat(scannableFile.extractedText()).isEqualTo(EXTRACTED_TEXT);
        softly.assertAll();
    }

    @Test
    void Should_ReturnSiteId_When_GetSourceIdCalled() {
        // Arrange
        var scannableFile = new SharePointScannableFile(SITE_ID, createDriveItem(), EXTRACTED_TEXT);

        // Act & Assert
        assertThat(scannableFile.getSourceId()).isEqualTo(SITE_ID);
    }

    @Test
    void Should_ReturnDriveItemId_When_GetIdCalled() {
        // Arrange
        var scannableFile = new SharePointScannableFile(SITE_ID, createDriveItem(), EXTRACTED_TEXT);

        // Act & Assert
        assertThat(scannableFile.getId()).isEqualTo(ITEM_ID);
    }

    @Test
    void Should_ReturnDriveItemName_When_GetTitleCalled() {
        // Arrange
        var scannableFile = new SharePointScannableFile(SITE_ID, createDriveItem(), EXTRACTED_TEXT);

        // Act & Assert
        assertThat(scannableFile.getTitle()).isEqualTo(FILE_NAME);
    }

    @Test
    void Should_ReturnExtractedText_When_GetContentBodyCalled() {
        // Arrange
        var scannableFile = new SharePointScannableFile(SITE_ID, createDriveItem(), EXTRACTED_TEXT);

        // Act & Assert
        assertThat(scannableFile.getContentBody()).isEqualTo(EXTRACTED_TEXT);
    }

    @Test
    void Should_ReturnNullContentBody_When_ExtractedTextIsNull() {
        // Arrange
        var scannableFile = new SharePointScannableFile(SITE_ID, createDriveItem(), null);

        // Act & Assert
        assertThat(scannableFile.getContentBody()).isNull();
    }

    @Test
    void Should_ReturnEmptyContentBody_When_ExtractedTextIsEmpty() {
        // Arrange
        var scannableFile = new SharePointScannableFile(SITE_ID, createDriveItem(), "");

        // Act & Assert
        assertThat(scannableFile.getContentBody()).isEmpty();
    }

    @Test
    void Should_ReturnMetadataWithDriveItemInfo_When_GetMetadataCalled() {
        // Arrange
        var scannableFile = new SharePointScannableFile(SITE_ID, createDriveItem(), EXTRACTED_TEXT);

        // Act
        Map<String, Object> metadata = scannableFile.getMetadata();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(metadata).containsEntry("driveId", DRIVE_ID);
        softly.assertThat(metadata).containsEntry("mimeType", MIME_TYPE);
        softly.assertThat(metadata).containsEntry("webUrl", WEB_URL);
        softly.assertThat(metadata).containsEntry("size", 2048L);
        softly.assertAll();
    }

    @Test
    void Should_ReturnMetadataWithDefaults_When_DriveItemHasNullOptionalFields() {
        // Arrange
        var driveItem = new SharePointDriveItem(
            ITEM_ID, FILE_NAME, null, null, null, null, null, false
        );
        var scannableFile = new SharePointScannableFile(SITE_ID, driveItem, EXTRACTED_TEXT);

        // Act
        Map<String, Object> metadata = scannableFile.getMetadata();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(metadata).containsEntry("driveId", "");
        softly.assertThat(metadata).containsEntry("mimeType", "");
        softly.assertThat(metadata).containsEntry("webUrl", "");
        softly.assertThat(metadata).containsEntry("size", 0L);
        softly.assertAll();
    }

    @Test
    void Should_ThrowException_When_SiteIdIsNull() {
        var driveItem = createDriveItem();
        assertThatThrownBy(() -> new SharePointScannableFile(null, driveItem, EXTRACTED_TEXT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("siteId cannot be null or blank");
    }

    @Test
    void Should_ThrowException_When_SiteIdIsBlank() {
        var driveItem = createDriveItem();
        assertThatThrownBy(() -> new SharePointScannableFile("  ", driveItem, EXTRACTED_TEXT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("siteId cannot be null or blank");
    }

    @Test
    void Should_ThrowException_When_DriveItemIsNull() {
        assertThatThrownBy(() -> new SharePointScannableFile(SITE_ID, null, EXTRACTED_TEXT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("driveItem cannot be null");
    }

    @Test
    void Should_ImplementScannableContent_When_Created() {
        // Arrange
        var scannableFile = new SharePointScannableFile(SITE_ID, createDriveItem(), EXTRACTED_TEXT);

        // Assert - verify the interface contract is fulfilled
        assertThat(scannableFile).isInstanceOf(pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent.class);
    }
}
