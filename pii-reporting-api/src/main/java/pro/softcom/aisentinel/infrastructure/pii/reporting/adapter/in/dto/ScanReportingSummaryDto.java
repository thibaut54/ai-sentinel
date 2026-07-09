package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto;

import java.time.Instant;
import java.util.List;

/**
 * REST representation of the dashboard spaces summary.
 *
 * @param scanId      business identifier of the latest scan
 * @param lastUpdated most recent observed event timestamp across spaces
 * @param spacesCount TOTAL number of spaces BEFORE filtering (for "X / Y" displays)
 * @param spaces      spaces after filter + search + sort
 * @param facets      contextual facet counts for the filter bar
 */
public record ScanReportingSummaryDto(
        String scanId,
        Instant lastUpdated,
        int spacesCount,
        List<SpaceSummaryDto> spaces,
        DashboardFacetsDto facets
) { }
