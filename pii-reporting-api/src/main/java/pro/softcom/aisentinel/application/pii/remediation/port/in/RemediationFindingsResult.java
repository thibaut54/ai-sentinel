package pro.softcom.aisentinel.application.pii.remediation.port.in;

import lombok.Builder;

import java.util.List;

/**
 * Server-computed remediation view: groups with aggregates over the full filtered scope,
 * findings paginated across groups.
 *
 * <p>{@code totals} reflects the lifecycle breakdown of the scope before the status facet
 * filter, so header counters stay stable while the operator switches facets.</p>
 */
@Builder(toBuilder = true)
public record RemediationFindingsResult(
        List<RemediationFindingGroup> groups,
        RemediationTotals totals,
        int page,
        int pageSize,
        long totalElements,
        long nonEligibleLegacyCount
) {

    public RemediationFindingsResult {
        groups = groups == null ? List.of() : List.copyOf(groups);
        if (totals == null) {
            throw new IllegalArgumentException("totals is required");
        }
    }
}
