package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * DTO for updating PII detection configuration via REST API.
 *
 * <p>Business purpose: Allows clients to modify detector enable/disable states
 * and confidence thresholds used by the PII detection service.
 *
 * <p>Validation: Ensures at least one detector is enabled and threshold is within valid range.
 *
 * @param glinerEnabled    Whether GLiNER detector should be enabled
 * @param presidioEnabled  Whether Presidio detector should be enabled
 * @param regexEnabled     Whether custom regex detector should be enabled
 * @param openmedEnabled   Whether OpenMed detector should be enabled
 * @param gliner2Enabled   Whether GLiNER2 detector should be enabled
 * @param defaultThreshold Default confidence threshold (0.0 to 1.0)
 * @param nbOfLabelByPass  Maximum labels per detector batch
 * @param llmJudgeEnabled  Whether the LLM-as-Judge post-filtering stage is enabled.
 *                         Optional in the payload: when omitted, defaults to {@code false}.
 */
public record UpdatePiiDetectionConfigRequestDto(
    @JsonProperty("glinerEnabled")
    @NotNull(message = "glinerEnabled is required")
    Boolean glinerEnabled,

    @JsonProperty("presidioEnabled")
    @NotNull(message = "presidioEnabled is required")
    Boolean presidioEnabled,

    @JsonProperty("regexEnabled")
    @NotNull(message = "regexEnabled is required")
    Boolean regexEnabled,

    @JsonProperty("openmedEnabled")
    @NotNull(message = "openmedEnabled is required")
    Boolean openmedEnabled,

    @JsonProperty("gliner2Enabled")
    @NotNull(message = "gliner2Enabled is required")
    Boolean gliner2Enabled,

    @JsonProperty("defaultThreshold")
    @NotNull(message = "defaultThreshold is required")
    @DecimalMin(value = "0.0", message = "Default threshold must be at least 0.0")
    @DecimalMax(value = "1.0", message = "Default threshold must be at most 1.0")
    BigDecimal defaultThreshold,

    @JsonProperty("nbOfLabelByPass")
    @NotNull(message = "nbOfLabelByPass is required")
    @DecimalMin(value = "1", message = "nbOfLabelByPass must be at least 1")
    Integer nbOfLabelByPass,

    @JsonProperty("llmJudgeEnabled")
    Boolean llmJudgeEnabled
) {
    /**
     * Validates business rules for the configuration request.
     *
     * <p>Business rule: At least one detector must be enabled to ensure
     * the system can perform PII detection.
     *
     * @throws IllegalArgumentException if no detectors are enabled
     */
    public UpdatePiiDetectionConfigRequestDto {
        if (notAtLeastOneAnalyserEnabled()) {
                throw new IllegalArgumentException("At least one detector must be enabled");
            }
    }

    /**
     * Returns the {@code llmJudgeEnabled} flag value with a {@code false} default
     * when the client omits the field. Keeps the MVP rollout zero-effect.
     */
    public boolean llmJudgeEnabledOrDefault() {
        return llmJudgeEnabled != null && llmJudgeEnabled;
    }

    private boolean notAtLeastOneAnalyserEnabled(){
        return glinerEnabled != null
            && presidioEnabled != null
            && regexEnabled != null
            && openmedEnabled != null
            && gliner2Enabled != null
            && !glinerEnabled
            && !presidioEnabled
            && !regexEnabled
            && !openmedEnabled
            && !gliner2Enabled;
    }
}