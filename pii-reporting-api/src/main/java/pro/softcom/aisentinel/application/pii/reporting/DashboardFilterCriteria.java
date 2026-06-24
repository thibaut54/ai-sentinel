package pro.softcom.aisentinel.application.pii.reporting;

import java.util.List;

/**
 * Server-side filter, search and sort criteria for the dashboard spaces summary.
 *
 * <p>Each axis is OR within itself and AND across axes. An empty axis means "no constraint".
 *
 * @param piiTypes   selected PII type codes (e.g. EMAIL, PHONE_NUMBER); empty = all
 * @param severities selected severity buckets (HIGH | MEDIUM | LOW); empty = all
 * @param statuses   selected UI status codes (NOT_STARTED | PENDING | ... | OK); empty = all
 * @param search     free-text search on space name or key, case-insensitive contains; null/blank = no search
 * @param sort       sort criterion (name | totalDetections | severityScore | lastScan | piiType:&lt;CODE&gt;); null = default
 * @param order      sort direction (asc | desc); null = default per criterion
 */
public record DashboardFilterCriteria(
    List<String> piiTypes,
    List<String> severities,
    List<String> statuses,
    String search,
    String sort,
    String order
) {

    public DashboardFilterCriteria {
        piiTypes = piiTypes == null ? List.of() : List.copyOf(piiTypes);
        severities = severities == null ? List.of() : List.copyOf(severities);
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }

    /**
     * Criteria with no constraint on any axis and default sorting.
     *
     * @return an empty criteria instance
     */
    public static DashboardFilterCriteria none() {
        return new DashboardFilterCriteria(List.of(), List.of(), List.of(), null, null, null);
    }
}
