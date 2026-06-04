package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating a single PII type configuration.
 *
 * @param detectorDescription Optional GLiNER2 inference description. When
 *        {@code null} (field omitted) the existing description is left
 *        unchanged ("absent = unchanged" semantics, spec §5.1). Only relevant
 *        for {@code GLINER2} rows.
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

        @Size(max = 2000, message = "Detector description must be at most 2000 characters")
        String detectorDescription
) {
}
