package pro.softcom.aisentinel.domain.pii.detection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Domain model for PII detection configuration.
 * Represents the configuration settings for PII detection detectors and thresholds.
 * This is the single source of truth for detection configuration in the system.
 * Detector must be one of: PRESIDIO, REGEX, MINISTRAL.
 *
 * <p>The {@code postfilterEnabled} flag activates the deterministic format
 * precision post-filter (IP/MAC/IBAN checksum) that runs after detection.
 */
public record PiiDetectionConfig(
        Integer id,
        boolean presidioEnabled,
        boolean regexEnabled,
        boolean ministralEnabled,
        Integer ministralChunkSize,
        Integer ministralOverlap,
        BigDecimal defaultThreshold,
        boolean postfilterEnabled,
        LocalDateTime updatedAt,
        String updatedBy) {

    private static final BigDecimal MIN_THRESHOLD = BigDecimal.ZERO;
    private static final BigDecimal MAX_THRESHOLD = BigDecimal.ONE;
    private static final int MIN_MINISTRAL_CHUNK_SIZE = 256;
    private static final int MAX_MINISTRAL_CHUNK_SIZE = 4096;

    /**
     * Compact constructor for validation.
     *
     * @throws IllegalArgumentException if threshold is out of range or other validation fails
     */
    public PiiDetectionConfig {
        if (defaultThreshold == null) {
            throw new IllegalArgumentException("Default threshold cannot be null");
        }

        if (defaultThreshold.compareTo(MIN_THRESHOLD) < 0) {
            throw new IllegalArgumentException(
                    "Default threshold must be greater than or equal to " + MIN_THRESHOLD);
        }

        if (defaultThreshold.compareTo(MAX_THRESHOLD) > 0) {
            throw new IllegalArgumentException(
                    "Default threshold must be less than or equal to " + MAX_THRESHOLD);
        }

        if (!presidioEnabled && !regexEnabled && !ministralEnabled) {
            throw new IllegalArgumentException(
                    "At least one detector must be enabled");
        }

        if (ministralChunkSize == null
                || ministralChunkSize < MIN_MINISTRAL_CHUNK_SIZE
                || ministralChunkSize > MAX_MINISTRAL_CHUNK_SIZE) {
            throw new IllegalArgumentException(
                    "Ministral chunk size must be between " + MIN_MINISTRAL_CHUNK_SIZE
                            + " and " + MAX_MINISTRAL_CHUNK_SIZE);
        }

        if (ministralOverlap == null
                || ministralOverlap < 0
                || ministralOverlap >= ministralChunkSize) {
            throw new IllegalArgumentException(
                    "Ministral overlap must be between 0 (inclusive) and the chunk size (exclusive)");
        }
    }
}
