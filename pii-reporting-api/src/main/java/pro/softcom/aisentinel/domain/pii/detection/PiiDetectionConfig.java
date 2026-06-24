package pro.softcom.aisentinel.domain.pii.detection;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Domain model for PII detection configuration.
 * Represents the configuration settings for PII detection detectors and thresholds.
 * This is the single source of truth for detection configuration in the system.
 * Detector must be one of: GLINER, PRESIDIO, REGEX, OPENMED, GLINER2, MINISTRAL.
 *
 * <p>The {@code llmJudgeEnabled} flag is a <strong>derived</strong> global guard:
 * it equals the logical OR of the five per-detector judge flags. The Python
 * detector service reads {@code llm_judge_enabled} as a global gate, so it must
 * stay {@code true} whenever at least one detector has its judge enabled
 * (cf. spec §1.4). Use {@link #computeGlobalLlmJudgeEnabled} to compute it.
 *
 * <p>The per-detector judge flags ({@code glinerJudgeEnabled},
 * {@code presidioJudgeEnabled}, {@code regexJudgeEnabled},
 * {@code openmedJudgeEnabled}, {@code gliner2JudgeEnabled}) route the LLM-as-Judge
 * post-filtering stage on a per-detector basis. They default to {@code false}.
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
        boolean ministralEnabled,
        Integer ministralChunkSize,
        Integer ministralOverlap,
        BigDecimal defaultThreshold,
        Integer nbOfLabelByPass,
        boolean llmJudgeEnabled,
        boolean glinerJudgeEnabled,
        boolean presidioJudgeEnabled,
        boolean regexJudgeEnabled,
        boolean openmedJudgeEnabled,
        boolean gliner2JudgeEnabled,
        boolean prefilterEnabled,
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

        if (!glinerEnabled && !presidioEnabled && !regexEnabled && !openmedEnabled
                && !gliner2Enabled && !ministralEnabled) {
            throw new IllegalArgumentException(
                    "At least one detector must be enabled");
        }

        if (nbOfLabelByPass == null || nbOfLabelByPass < 1) {
            throw new IllegalArgumentException(
                    "Number of labels by pass must be at least 1");
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

    /**
     * Computes the derived global LLM-judge guard as the logical OR of the five
     * per-detector judge flags. The Python detector service reads
     * {@code llm_judge_enabled} as a global gate, so it must be {@code true}
     * whenever at least one detector has its judge enabled.
     *
     * @return {@code true} if any per-detector judge flag is enabled
     */
    public static boolean computeGlobalLlmJudgeEnabled(
            boolean glinerJudgeEnabled,
            boolean presidioJudgeEnabled,
            boolean regexJudgeEnabled,
            boolean openmedJudgeEnabled,
            boolean gliner2JudgeEnabled) {
        return glinerJudgeEnabled
                || presidioJudgeEnabled
                || regexJudgeEnabled
                || openmedJudgeEnabled
                || gliner2JudgeEnabled;
    }
}