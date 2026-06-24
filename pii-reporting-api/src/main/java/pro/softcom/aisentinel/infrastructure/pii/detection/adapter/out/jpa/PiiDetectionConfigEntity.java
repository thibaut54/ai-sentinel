package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * JPA Entity for PII detection configuration.
 * Represents the database table for storing PII detection settings.
 * Single-row configuration table (id always = 1).
 */
@Setter
@Getter
@Entity
@Table(name = "pii_detection_config")
@Builder
@AllArgsConstructor
public class PiiDetectionConfigEntity {

    @Id
    @Column(nullable = false)
    private Integer id;

    @NotNull
    @Column(name = "gliner_enabled", nullable = false)
    private Boolean glinerEnabled;

    @NotNull
    @Column(name = "presidio_enabled", nullable = false)
    private Boolean presidioEnabled;

    @NotNull
    @Column(name = "regex_enabled", nullable = false)
    private Boolean regexEnabled;

    @NotNull
    @Column(name = "openmed_enabled", nullable = false)
    private Boolean openmedEnabled;

    /**
     * Activates the GLiNER2 detector (ensemble source). Defaults to
     * {@code false} for an explicit operator opt-in (spec D4).
     */
    @NotNull
    @Column(name = "gliner2_enabled", nullable = false)
    private Boolean gliner2Enabled;

    /**
     * Activates the Ministral-PII detector (specialised LLM source). Defaults to
     * {@code false} for an explicit operator opt-in.
     */
    @NotNull
    @Column(name = "ministral_enabled", nullable = false)
    private Boolean ministralEnabled;

    /**
     * Sliding-window chunk size (characters) used by the Ministral-PII detector.
     */
    @NotNull
    @Column(name = "ministral_chunk_size", nullable = false)
    private Integer ministralChunkSize;

    /**
     * Sliding-window overlap (characters) used by the Ministral-PII detector.
     */
    @NotNull
    @Column(name = "ministral_overlap", nullable = false)
    private Integer ministralOverlap;

    /**
     * Routes the LLM-as-Judge stage for Ministral-PII findings. Persisted as a
     * database column only; it is force-set to {@code false} by the adapter
     * because the specialised model is permanently exempt from the judge.
     */
    @NotNull
    @Column(name = "ministral_judge_enabled", nullable = false)
    private Boolean ministralJudgeEnabled;

    @NotNull
    @DecimalMin(value = "0.0", message = "Default threshold must be at least 0.0")
    @DecimalMax(value = "1.0", message = "Default threshold must be at most 1.0")
    @Column(name = "default_threshold", nullable = false, precision = 3, scale = 2)
    private BigDecimal defaultThreshold;

    @Column(name = "nb_of_label_by_pass", nullable = false)
    @NotNull
    @Min(value = 1, message = "nbOfLabelByPass must be >= 1")
    private Integer nbOfLabelByPass;

    /**
     * Activates the LLM-as-Judge post-filtering stage (cf. spec §1.4).
     * Defaults to {@code false} for a zero-effect MVP rollout.
     */
    @NotNull
    @Column(name = "llm_judge_enabled", nullable = false)
    private Boolean llmJudgeEnabled;

    /**
     * Routes the LLM-as-Judge stage for GLiNER findings. Defaults to
     * {@code false} for an explicit operator opt-in.
     */
    @NotNull
    @Column(name = "gliner_judge_enabled", nullable = false)
    private Boolean glinerJudgeEnabled;

    /**
     * Routes the LLM-as-Judge stage for Presidio findings. Defaults to
     * {@code false} for an explicit operator opt-in.
     */
    @NotNull
    @Column(name = "presidio_judge_enabled", nullable = false)
    private Boolean presidioJudgeEnabled;

    /**
     * Routes the LLM-as-Judge stage for regex findings. Defaults to
     * {@code false} for an explicit operator opt-in.
     */
    @NotNull
    @Column(name = "regex_judge_enabled", nullable = false)
    private Boolean regexJudgeEnabled;

    /**
     * Routes the LLM-as-Judge stage for OpenMed findings. Defaults to
     * {@code false} for an explicit operator opt-in.
     */
    @NotNull
    @Column(name = "openmed_judge_enabled", nullable = false)
    private Boolean openmedJudgeEnabled;

    /**
     * Routes the LLM-as-Judge stage for GLiNER2 findings. Defaults to
     * {@code false} for an explicit operator opt-in.
     */
    @NotNull
    @Column(name = "gliner2_judge_enabled", nullable = false)
    private Boolean gliner2JudgeEnabled;

    /**
     * Activates the deterministic format pre-filter (IP/MAC/IBAN checksum) that
     * runs before the LLM judge. Defaults to {@code false} for a zero-effect rollout.
     */
    @NotNull
    @Column(name = "prefilter_enabled", nullable = false)
    private Boolean prefilterEnabled;

    @NotNull
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    protected PiiDetectionConfigEntity() {
        // Required by JPA
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PiiDetectionConfigEntity that = (PiiDetectionConfigEntity) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}