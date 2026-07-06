package pro.softcom.aisentinel.domain.pii.remediation;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;

/**
 * Immutable reference to a detected PII finding, stable across scans.
 *
 * <p>The identity deliberately excludes {@code scanId} and character offsets: both change on
 * every re-scan and would orphan remediation statuses and false-positive feedback. It also
 * excludes {@code severity}, so a severity recalibration keeps the finding identity intact.</p>
 */
@Builder(toBuilder = true)
public record FindingReference(
        String spaceKey,
        String pageId,
        String attachmentName,
        String detector,
        String piiType,
        PersonallyIdentifiableInformationSeverity severity,
        String valueFingerprint
) {

    private static final String CANONICAL_SEPARATOR = "\n";

    public FindingReference {
        requireNonBlank(spaceKey, "spaceKey");
        requireNonBlank(pageId, "pageId");
        requireNonBlank(detector, "detector");
        requireNonBlank(piiType, "piiType");
        requireNonBlank(valueFingerprint, "valueFingerprint");
        if (severity == null) {
            throw new IllegalArgumentException("severity is required");
        }
        if (attachmentName != null && attachmentName.isBlank()) {
            throw new IllegalArgumentException("attachmentName must not be blank when provided");
        }
    }

    /**
     * Derives the stable finding identifier: SHA-256 hex of the identity fields
     * {@code (spaceKey, pageId, attachmentName, detector, piiType, valueFingerprint)}
     * joined in that order by a line feed, a null {@code attachmentName} being encoded
     * as an empty string (a provided attachment name is never blank, so the encoding
     * is unambiguous).
     */
    public String findingId() {
        String canonical = String.join(CANONICAL_SEPARATOR,
                spaceKey,
                pageId,
                attachmentName == null ? "" : attachmentName,
                detector,
                piiType,
                valueFingerprint);
        return Sha256.hexOf(canonical);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }
}
