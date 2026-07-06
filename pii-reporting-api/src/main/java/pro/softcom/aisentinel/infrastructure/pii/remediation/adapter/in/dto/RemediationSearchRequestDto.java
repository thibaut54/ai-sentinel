package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

/**
 * Body of {@code POST /findings/search}: view scope, grouping mode, facet filters,
 * pagination and the current selection (resolved server-side).
 */
public record RemediationSearchRequestDto(
        RemediationScopeDto scope,
        String groupBy,
        String statusFilter,
        String searchText,
        String itemFilter,
        Integer page,
        Integer pageSize,
        RemediationSelectionDto selection
) {
}
