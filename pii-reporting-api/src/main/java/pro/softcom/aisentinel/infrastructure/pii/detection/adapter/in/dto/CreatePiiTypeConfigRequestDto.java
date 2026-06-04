package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a new PII type configuration (custom label).
 *
 * @param detectorDescription Optional GLiNER2 inference description (only
 *        relevant for {@code GLINER2} rows).
 */
public record CreatePiiTypeConfigRequestDto(
        @NotBlank(message = "PII type cannot be blank")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,98}$",
                message = "PII type must be UPPER_SNAKE_CASE (2-99 chars, starts with letter)")
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

        @Size(max = 2000, message = "Detector description must be at most 2000 characters")
        String detectorDescription,

        String countryCode,

        @Pattern(regexp = "^(HIGH|MEDIUM|LOW)$", message = "Severity must be HIGH, MEDIUM, or LOW")
        String severity
) {
}
