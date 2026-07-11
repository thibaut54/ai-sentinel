package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Request DTO for promoting a MINISTRAL discovered label to a custom PII type.
 * <p>
 * The label itself comes from the path; this body carries the configuration
 * attributes the operator chooses for it.
 */
public record PromoteDiscoveredLabelRequestDto(
        @NotBlank(message = "Category cannot be blank")
        String category,

        @Pattern(regexp = "^(HIGH|MEDIUM|LOW)$", message = "Severity must be HIGH, MEDIUM, or LOW")
        String severity,

        @NotNull(message = "Threshold is required")
        @DecimalMin(value = "0.0", message = "Threshold must be at least 0.0")
        @DecimalMax(value = "1.0", message = "Threshold must be at most 1.0")
        Double threshold,

        String detectorLabel,

        String countryCode
) {
}
