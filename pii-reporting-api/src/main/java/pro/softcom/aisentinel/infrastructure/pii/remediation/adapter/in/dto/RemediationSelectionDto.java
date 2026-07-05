package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

import java.util.List;

/**
 * Criteria-based selection as submitted by the frontend; the backend resolves it
 * into concrete findings.
 */
public record RemediationSelectionDto(
        RemediationScopeDto scope,
        List<String> piiTypes,
        List<String> severities,
        List<String> excludedFindingIds,
        List<String> includedFindingIds
) {
}
