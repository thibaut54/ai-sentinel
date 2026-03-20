package pro.softcom.aisentinel.domain.sharepoint;

import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;

import java.util.Map;

/**
 * Wraps a SharePoint drive item with its extracted text content for PII scanning.
 * Implements {@link ScannableContent} to integrate with the generic scanning pipeline.
 *
 * @param siteId        identifier of the SharePoint site containing the file
 * @param driveItem     the SharePoint file metadata
 * @param extractedText text content extracted from the file (PDF, DOCX, etc.)
 */
public record SharePointScannableFile(
    String siteId,
    SharePointDriveItem driveItem,
    String extractedText
) implements ScannableContent {

    public SharePointScannableFile {
        if (siteId == null || siteId.isBlank()) {
            throw new IllegalArgumentException("siteId cannot be null or blank");
        }
        if (driveItem == null) {
            throw new IllegalArgumentException("driveItem cannot be null");
        }
    }

    @Override
    public String getSourceId() {
        return siteId;
    }

    @Override
    public String getId() {
        return driveItem.id();
    }

    @Override
    public String getTitle() {
        return driveItem.name();
    }

    @Override
    public String getContentBody() {
        return extractedText;
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of(
            "driveId", driveItem.driveId() != null ? driveItem.driveId() : "",
            "mimeType", driveItem.mimeType() != null ? driveItem.mimeType() : "",
            "webUrl", driveItem.webUrl() != null ? driveItem.webUrl() : "",
            "size", driveItem.size() != null ? driveItem.size() : 0L
        );
    }
}
