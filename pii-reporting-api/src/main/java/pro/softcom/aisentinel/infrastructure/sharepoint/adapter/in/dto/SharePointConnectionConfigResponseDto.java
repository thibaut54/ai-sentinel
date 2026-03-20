package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.dto;

import java.time.Instant;

public record SharePointConnectionConfigResponseDto(
        String tenantId,
        String clientId,
        String clientSecretMasked,
        boolean enabled,
        Instant updatedAt,
        String updatedBy,
        boolean configured
) {
}
