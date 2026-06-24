package pro.softcom.aisentinel.domain.pii.reporting;

import java.util.Map;

/**
 * Contextual facet counts for the dashboard filter bar.
 *
 * <p>Each axis is computed over the spaces matching the OTHER axes plus the free-text search,
 * excluding the axis's own selection (faceted-search semantics).
 *
 * @param piiTypes   facet counts keyed by PII type code (e.g. EMAIL, PHONE_NUMBER)
 * @param severities facet counts keyed by severity (HIGH | MEDIUM | LOW)
 * @param statuses   facet counts keyed by UI status code (NOT_STARTED | PENDING | ... | OK | FAILED | INTERRUPTED)
 */
public record DashboardFacets(
    Map<String, FacetCount> piiTypes,
    Map<String, FacetCount> severities,
    Map<String, FacetCount> statuses
) {

    public DashboardFacets {
        if (piiTypes == null) {
            piiTypes = Map.of();
        }
        if (severities == null) {
            severities = Map.of();
        }
        if (statuses == null) {
            statuses = Map.of();
        }
    }

    /**
     * Facets with no counts on any axis.
     *
     * @return an empty facets instance
     */
    public static DashboardFacets empty() {
        return new DashboardFacets(Map.of(), Map.of(), Map.of());
    }
}
