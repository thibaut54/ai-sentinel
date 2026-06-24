package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * @param ministralEnabled    Whether the Ministral-PII detector should be enabled
 * @param ministralChunkSize  Sliding-window chunk size (characters) for the Ministral-PII detector (256-4096)
 * @param ministralOverlap    Sliding-window overlap (characters) for the Ministral-PII detector (0-512, less than the chunk size)
 * @param defaultThreshold Default confidence threshold (0.0 to 1.0)
 * @param nbOfLabelByPass     Maximum labels per detector batch
 * @param llmJudgeEnabled     Deprecated/ignored: the global guard is now derived server-side
 *                            as the OR of the five per-detector judge flags. Kept optional for
 *                            backward compatibility; any incoming value is ignored.
 * @param glinerJudgeEnabled  Whether the LLM-as-Judge stage runs on GLiNER findings.
 *                            Optional: when omitted, defaults to {@code false}.
 * @param presidioJudgeEnabled Whether the LLM-as-Judge stage runs on Presidio findings.
 *                            Optional: when omitted, defaults to {@code false}.
 * @param regexJudgeEnabled   Whether the LLM-as-Judge stage runs on regex findings.
 *                            Optional: when omitted, defaults to {@code false}.
 * @param openmedJudgeEnabled Whether the LLM-as-Judge stage runs on OpenMed findings.
 *                            Optional: when omitted, defaults to {@code false}.
 * @param gliner2JudgeEnabled Whether the LLM-as-Judge stage runs on GLiNER2 findings.
 *                            Optional: when omitted, defaults to {@code false}.
 * @param prefilterEnabled    Whether the deterministic format pre-filter stage is enabled.
 *                            Optional in the payload: when omitted, defaults to {@code false}.
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

    @JsonProperty("ministralEnabled")
    @NotNull(message = "ministralEnabled is required")
    Boolean ministralEnabled,

    @JsonProperty("ministralChunkSize")
    @NotNull(message = "ministralChunkSize is required")
    @Min(value = 256, message = "ministralChunkSize must be at least 256")
    @Max(value = 4096, message = "ministralChunkSize must be at most 4096")
    Integer ministralChunkSize,

    @JsonProperty("ministralOverlap")
    @NotNull(message = "ministralOverlap is required")
    @Min(value = 0, message = "ministralOverlap must be at least 0")
    @Max(value = 512, message = "ministralOverlap must be at most 512")
    Integer ministralOverlap,

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
    Boolean llmJudgeEnabled,

    @JsonProperty("glinerJudgeEnabled")
    Boolean glinerJudgeEnabled,

    @JsonProperty("presidioJudgeEnabled")
    Boolean presidioJudgeEnabled,

    @JsonProperty("regexJudgeEnabled")
    Boolean regexJudgeEnabled,

    @JsonProperty("openmedJudgeEnabled")
    Boolean openmedJudgeEnabled,

    @JsonProperty("gliner2JudgeEnabled")
    Boolean gliner2JudgeEnabled,

    @JsonProperty("prefilterEnabled")
    Boolean prefilterEnabled
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
     * Cross-field rule: the Ministral overlap must be strictly smaller than the
     * chunk size. Returns {@code true} when either value is {@code null} so the
     * {@link NotNull} constraints report the missing field instead.
     */
    @AssertTrue(message = "ministralOverlap must be less than ministralChunkSize")
    public boolean isMinistralOverlapLessThanChunkSize() {
        if (ministralOverlap == null || ministralChunkSize == null) {
            return true;
        }
        return ministralOverlap < ministralChunkSize;
    }

    /**
     * Returns the {@code ministralEnabled} flag value with a {@code false} default
     * when the client omits the field.
     */
    public boolean ministralEnabledOrDefault() {
        return ministralEnabled != null && ministralEnabled;
    }

    /**
     * Returns the {@code ministralChunkSize} value with a {@code 1024} default
     * when the client omits the field.
     */
    public int ministralChunkSizeOrDefault() {
        return ministralChunkSize != null ? ministralChunkSize : 1024;
    }

    /**
     * Returns the {@code ministralOverlap} value with a {@code 128} default
     * when the client omits the field.
     */
    public int ministralOverlapOrDefault() {
        return ministralOverlap != null ? ministralOverlap : 128;
    }

    /**
     * Returns the {@code llmJudgeEnabled} flag value with a {@code false} default
     * when the client omits the field. Keeps the MVP rollout zero-effect.
     */
    public boolean llmJudgeEnabledOrDefault() {
        return llmJudgeEnabled != null && llmJudgeEnabled;
    }

    /**
     * Returns the {@code glinerJudgeEnabled} flag with a {@code false} default
     * when the client omits the field.
     */
    public boolean glinerJudgeEnabledOrDefault() {
        return glinerJudgeEnabled != null && glinerJudgeEnabled;
    }

    /**
     * Returns the {@code presidioJudgeEnabled} flag with a {@code false} default
     * when the client omits the field.
     */
    public boolean presidioJudgeEnabledOrDefault() {
        return presidioJudgeEnabled != null && presidioJudgeEnabled;
    }

    /**
     * Returns the {@code regexJudgeEnabled} flag with a {@code false} default
     * when the client omits the field.
     */
    public boolean regexJudgeEnabledOrDefault() {
        return regexJudgeEnabled != null && regexJudgeEnabled;
    }

    /**
     * Returns the {@code openmedJudgeEnabled} flag with a {@code false} default
     * when the client omits the field.
     */
    public boolean openmedJudgeEnabledOrDefault() {
        return openmedJudgeEnabled != null && openmedJudgeEnabled;
    }

    /**
     * Returns the {@code gliner2JudgeEnabled} flag with a {@code false} default
     * when the client omits the field.
     */
    public boolean gliner2JudgeEnabledOrDefault() {
        return gliner2JudgeEnabled != null && gliner2JudgeEnabled;
    }

    /**
     * Returns the {@code prefilterEnabled} flag value with a {@code false} default
     * when the client omits the field. Keeps the rollout zero-effect.
     */
    public boolean prefilterEnabledOrDefault() {
        return prefilterEnabled != null && prefilterEnabled;
    }

    private boolean notAtLeastOneAnalyserEnabled(){
        return glinerEnabled != null
            && presidioEnabled != null
            && regexEnabled != null
            && openmedEnabled != null
            && gliner2Enabled != null
            && ministralEnabled != null
            && !glinerEnabled
            && !presidioEnabled
            && !regexEnabled
            && !openmedEnabled
            && !gliner2Enabled
            && !ministralEnabled;
    }
}