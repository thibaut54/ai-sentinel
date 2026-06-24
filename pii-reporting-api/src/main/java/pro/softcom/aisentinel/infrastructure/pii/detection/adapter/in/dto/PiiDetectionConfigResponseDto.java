package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO representing PII detection configuration for REST API responses.
 *
 * <p>Business purpose: Provides clients with current detector enable/disable states
 * and confidence thresholds used by the PII detection service.
 *
 * @param glinerEnabled    Whether GLiNER detector is enabled
 * @param presidioEnabled  Whether Presidio detector is enabled
 * @param regexEnabled     Whether custom regex detector is enabled
 * @param openmedEnabled   Whether OpenMed detector is enabled
 * @param gliner2Enabled   Whether GLiNER2 detector is enabled
 * @param ministralEnabled    Whether the Ministral-PII detector is enabled
 * @param ministralChunkSize  Sliding-window chunk size (tokens) for the Ministral-PII detector
 * @param ministralOverlap    Sliding-window overlap (tokens) for the Ministral-PII detector
 * @param defaultThreshold Default confidence threshold (0.0 to 1.0)
 * @param nbOfLabelByPass     Maximum labels per detector batch
 * @param llmJudgeEnabled     Derived global LLM-judge guard (OR of the five per-detector flags)
 * @param glinerJudgeEnabled  Whether the LLM-as-Judge stage runs on GLiNER findings
 * @param presidioJudgeEnabled Whether the LLM-as-Judge stage runs on Presidio findings
 * @param regexJudgeEnabled   Whether the LLM-as-Judge stage runs on regex findings
 * @param openmedJudgeEnabled Whether the LLM-as-Judge stage runs on OpenMed findings
 * @param gliner2JudgeEnabled Whether the LLM-as-Judge stage runs on GLiNER2 findings
 * @param prefilterEnabled    Whether the deterministic format pre-filter stage is enabled
 * @param updatedAt           Timestamp of last configuration update
 * @param updatedBy           User who last updated the configuration
 */
public record PiiDetectionConfigResponseDto(
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
    String updatedBy
) {
}