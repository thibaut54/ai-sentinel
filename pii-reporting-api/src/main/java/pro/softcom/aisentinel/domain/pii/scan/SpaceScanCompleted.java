package pro.softcom.aisentinel.domain.pii.scan;

/**
 * Domain event signaling the completion of a space scan.
 * This event encapsulates the business information needed to identify a completed scan.
 */
public record SpaceScanCompleted(String scanId, String spaceKey, String sourceType) {

    public SpaceScanCompleted {
        if (scanId == null || scanId.isBlank()) {
            throw new IllegalArgumentException("scanId cannot be empty");
        }
        if (spaceKey == null || spaceKey.isBlank()) {
            throw new IllegalArgumentException("spaceKey cannot be empty");
        }
        if (sourceType == null || sourceType.isBlank()) {
            throw new IllegalArgumentException("sourceType cannot be empty");
        }
    }
}
