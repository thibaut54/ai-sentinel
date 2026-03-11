package pro.softcom.aisentinel.domain.pii.scan;

import pro.softcom.aisentinel.domain.pii.export.SourceType;

/**
 * Domain event signaling the completion of a source scan.
 * This event encapsulates the business information needed to identify a completed scan.
 */
public record SpaceScanCompleted(String scanId, String sourceKey, SourceType sourceType) {

    public SpaceScanCompleted {
        if (scanId == null || scanId.isBlank()) {
            throw new IllegalArgumentException("scanId cannot be empty");
        }
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey cannot be empty");
        }
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType cannot be null");
        }
    }
}
