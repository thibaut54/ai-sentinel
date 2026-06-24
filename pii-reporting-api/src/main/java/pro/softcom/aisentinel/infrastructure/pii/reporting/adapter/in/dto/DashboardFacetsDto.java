package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto;

import java.util.Map;

/**
 * REST representation of the contextual facet counts for the dashboard filter bar.
 *
 * @param piiTypes   facet counts keyed by PII type code (e.g. EMAIL, PHONE_NUMBER)
 * @param severities facet counts keyed by severity (HIGH | MEDIUM | LOW)
 * @param statuses   facet counts keyed by UI status code (NOT_STARTED | PENDING | ... | OK)
 */
public record DashboardFacetsDto(
    Map<String, FacetCountDto> piiTypes,
    Map<String, FacetCountDto> severities,
    Map<String, FacetCountDto> statuses
) {
}
