package pro.softcom.aisentinel.domain.pii.remediation;

/**
 * Domain event signaling that the set of false-positive findings of a space changed for a scan,
 * either because findings were reported as false positives or because false positives were
 * restored. Read models derived from the scan events (such as the exported detection report)
 * must be refreshed for this (scan, space) pair.
 */
public record SpaceFalsePositivesChanged(String scanId, String spaceKey) {

    public SpaceFalsePositivesChanged {
        if (scanId == null || scanId.isBlank()) {
            throw new IllegalArgumentException("scanId cannot be empty");
        }
        if (spaceKey == null || spaceKey.isBlank()) {
            throw new IllegalArgumentException("spaceKey cannot be empty");
        }
    }
}
