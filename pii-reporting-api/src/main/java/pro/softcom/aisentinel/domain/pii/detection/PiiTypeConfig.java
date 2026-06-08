package pro.softcom.aisentinel.domain.pii.detection;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents configuration for a specific PII type within a detector.
 * <p>
 * Business rules:
 * - Each PII type + detector combination must be unique
 * - Threshold must be between 0.0 and 1.0
 * - Detector must be one of: GLINER, PRESIDIO, REGEX
 */
@Getter
@Builder
public class PiiTypeConfig {

    // Getters
    private final Long id;
    private final String piiType;
    private final String detector;
    private final boolean enabled;
    private final double threshold;
    private final String category;
    private final String countryCode;
    /**
     * Natural language label used by the detector for PII identification.
     * <p>
     * Business purpose: Decouples internal PII type codes from detector-specific labels.
     * For example, GLINER uses "email" while our system uses "EMAIL".
     * This enables runtime configuration of detector behavior without code changes.
     * <p>
     * Examples:
     * - "email" for EMAIL type
     * - "credit card number" for CREDITCARDNUMBER type
     * - "person name" for PERSONNAME type
     */
    private final String detectorLabel;
    /**
     * Natural-language inference description passed to GLiNER2
     * ({@code {detectorLabel: detectorDescription}}).
     * <p>
     * Business purpose: GLiNER2 needs BOTH a label (entity key) and a
     * description (zero-shot disambiguation prompt). This field is distinct from
     * {@link #detectorLabel} and is only meaningful for {@code GLINER2} rows;
     * {@code null} for the other detectors. Editable at runtime via the UI.
     */
    private final String detectorDescription;
    /**
     * Enables the LLM-as-Judge post-filter for this PII type.
     * <p>
     * Business purpose: lets an operator opt a single PII type out of the
     * LLM-as-Judge stage while keeping it on for the rest. Defaults to
     * {@code true}. Only effective when the global {@code llmJudgeEnabled} flag
     * (on {@code PiiDetectionConfig}) is on.
     */
    @Default
    private final boolean llmJudgeEnabled = true;
    private final boolean custom;
    private final String severity;
    private final LocalDateTime updatedAt;
    private final String updatedBy;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PiiTypeConfig that = (PiiTypeConfig) o;
        return Objects.equals(piiType, that.piiType) &&
                Objects.equals(detector, that.detector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(piiType, detector);
    }

    @Override
    public String toString() {
        return "PiiTypeConfig{" +
                "id=" + id +
                ", piiType='" + piiType + '\'' +
                ", detector='" + detector + '\'' +
                ", enabled=" + enabled +
                ", threshold=" + threshold +
                ", category='" + category + '\'' +
                ", countryCode='" + countryCode + '\'' +
                ", detectorDescription='" + detectorDescription + '\'' +
                ", llmJudgeEnabled=" + llmJudgeEnabled +
                ", custom=" + custom +
                ", severity='" + severity + '\'' +
                ", updatedAt=" + updatedAt +
                ", updatedBy='" + updatedBy + '\'' +
                '}';
    }
}