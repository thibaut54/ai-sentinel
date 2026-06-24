package pro.softcom.aisentinel.domain.pii.reporting;

import java.time.Instant;
import java.util.List;

/**
 * Scan reporting summary combining scan metadata with per-space aggregated data.
 * This is the single source of truth for dashboard display, combining:
 * - Authoritative status and progress from scan_checkpoints
 * - Aggregated counters from scan_events
 * - Contextual facet counts for the filter bar
 *
 * @param scanId      business identifier of the latest scan
 * @param lastUpdated most recent observed event timestamp across spaces
 * @param spacesCount TOTAL number of spaces BEFORE filtering (for "X / Y" displays)
 * @param spaces      spaces after filter + search + sort
 * @param facets      contextual facet counts for the filter bar
 */
public record ScanReportingSummary(
    String scanId,
    Instant lastUpdated,
    int spacesCount,
    List<SpaceSummary> spaces,
    DashboardFacets facets
) {
}
