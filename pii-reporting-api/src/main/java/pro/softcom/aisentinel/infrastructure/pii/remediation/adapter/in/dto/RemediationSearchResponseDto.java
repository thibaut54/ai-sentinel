package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

import java.util.List;

/**
 * Grouped, paginated remediation view with server-computed aggregates.
 */
public record RemediationSearchResponseDto(
        List<RemediationGroupDto> groups,
        RemediationTotalsDto totals,
        int page,
        int pageSize,
        long totalElements,
        long totalGroups,
        long nonEligibleLegacyCount
) {
}
