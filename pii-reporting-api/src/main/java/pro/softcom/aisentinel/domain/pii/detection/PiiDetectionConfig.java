package pro.softcom.aisentinel.domain.pii.detection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Domain model for PII detection configuration.
 * Represents the configuration settings for PII detection detectors and thresholds.
 * This is the single source of truth for detection configuration in the system.
 * Detector must be one of: GLINER, PRESIDIO, REGEX, OPENMED, GLINER2.
 *
 * <p>The {@code llmJudgeEnabled} flag activates the LLM-as-Judge post-filtering
 * stage that audits GLiNER findings to reduce false positives (cf. spec §1.4).
 *
 * <p>The {@code prefilterEnabled} flag activates the deterministic format
 * pre-filter (IP/MAC/IBAN checksum) that runs before the LLM judge.
 */
public record PiiDetectionConfig(
        Integer id,
        boolean glinerEnabled,
        boolean presidioEnabled,
        boolean regexEnabled,
        boolean openmedEnabled,
        boolean gliner2Enabled,
        BigDecimal defaultThreshold,
        Integer nbOfLabelByPass,
        boolean llmJudgeEnabled,
        boolean prefilterEnabled,
        LocalDateTime updatedAt,
        String updatedBy) {

    private static final BigDecimal MIN_THRESHOLD = BigDecimal.ZERO;
    private static final BigDecimal MAX_THRESHOLD = BigDecimal.ONE;

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

        if (!glinerEnabled && !presidioEnabled && !regexEnabled && !openmedEnabled && !gliner2Enabled) {
            throw new IllegalArgumentException(
                    "At least one detector must be enabled");
        }

        if (nbOfLabelByPass == null || nbOfLabelByPass < 1) {
            throw new IllegalArgumentException(
                    "Number of labels by pass must be at least 1");
        }
    }
}