package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateSharePointConnectionConfigRequestDto(
        @NotBlank String tenantId,
        @NotBlank String clientId,
        String clientSecret,
        boolean enabled
) {
}
