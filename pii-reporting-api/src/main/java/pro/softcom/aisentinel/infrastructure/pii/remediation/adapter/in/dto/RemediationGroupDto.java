package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One accordion group (PII type or severity) with aggregates over the whole scope;
 * {@code findings} only carries the current page members.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemediationGroupDto(
        String key,
        String label,
        String severity,
        long total,
        long occurrenceCount,
        long selectedCount,
        String masterState,
        List<RemediationFindingDto> findings
) {
}
