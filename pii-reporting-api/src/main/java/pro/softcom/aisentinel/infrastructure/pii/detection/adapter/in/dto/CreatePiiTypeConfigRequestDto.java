package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for creating a new PII type configuration (custom label).
 */
public record CreatePiiTypeConfigRequestDto(
        @NotBlank(message = "PII type cannot be blank")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,98}$",
                message = "PII type must be UPPER_SNAKE_CASE (3-99 chars, starts with letter)")
        String piiType,

        @NotBlank(message = "Detector cannot be blank")
        String detector,

        @NotNull(message = "Enabled status is required")
        Boolean enabled,

        @NotNull(message = "Threshold is required")
        @DecimalMin(value = "0.0", message = "Threshold must be at least 0.0")
        @DecimalMax(value = "1.0", message = "Threshold must be at most 1.0")
        Double threshold,

        @NotBlank(message = "Category cannot be blank")
        String category,

        String detectorLabel,

        String countryCode,

        @Pattern(regexp = "^(HIGH|MEDIUM|LOW)$", message = "Severity must be HIGH, MEDIUM, or LOW")
        String severity,

        @NotNull(message = "GDPR classification is required")
        @Pattern(regexp = "SPECIAL_CATEGORY|CRIMINAL_DATA|PERSONAL_DATA_HIGH_RISK|PERSONAL_DATA",
                message = "GDPR classification must be one of: SPECIAL_CATEGORY, CRIMINAL_DATA, PERSONAL_DATA_HIGH_RISK, PERSONAL_DATA")
        String gdprClassification,

        @NotNull(message = "nLPD classification is required")
        @Pattern(regexp = "SENSITIVE_DATA|HIGH_RISK_PROFILING_DATA|PERSONAL_DATA_HIGH_RISK|PERSONAL_DATA",
                message = "nLPD classification must be one of: SENSITIVE_DATA, HIGH_RISK_PROFILING_DATA, PERSONAL_DATA_HIGH_RISK, PERSONAL_DATA")
        String nlpdClassification
) {
}
