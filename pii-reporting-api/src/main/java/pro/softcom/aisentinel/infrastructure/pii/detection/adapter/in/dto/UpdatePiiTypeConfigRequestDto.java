package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for updating a single PII type configuration.
 * <p>
 * Classification fields are optional: {@code null} means the current value in DB
 * must be preserved.
 */
public record UpdatePiiTypeConfigRequestDto(
        @NotBlank(message = "PII type cannot be blank")
        String piiType,

        @NotBlank(message = "Detector cannot be blank")
        String detector,

        @NotNull(message = "Enabled status is required")
        Boolean enabled,

        @NotNull(message = "Threshold is required")
        @DecimalMin(value = "0.0", message = "Threshold must be at least 0.0")
        @DecimalMax(value = "1.0", message = "Threshold must be at most 1.0")
        Double threshold,

        @Pattern(regexp = "SPECIAL_CATEGORY|CRIMINAL_DATA|PERSONAL_DATA_HIGH_RISK|PERSONAL_DATA",
                message = "GDPR classification must be one of: SPECIAL_CATEGORY, CRIMINAL_DATA, PERSONAL_DATA_HIGH_RISK, PERSONAL_DATA")
        String gdprClassification,

        @Pattern(regexp = "SENSITIVE_DATA|HIGH_RISK_PROFILING_DATA|PERSONAL_DATA_HIGH_RISK|PERSONAL_DATA",
                message = "nLPD classification must be one of: SENSITIVE_DATA, HIGH_RISK_PROFILING_DATA, PERSONAL_DATA_HIGH_RISK, PERSONAL_DATA")
        String nlpdClassification
) {
}
