package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.dto;

import jakarta.validation.constraints.NotBlank;

public record TestSharePointConnectionRequestDto(
        @NotBlank String tenantId,
        @NotBlank String clientId,
        String clientSecret
) {
}
