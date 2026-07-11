package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.reporting.service.DashboardFalsePositiveFilter;
import pro.softcom.aisentinel.domain.pii.reporting.DashboardFacets;
import pro.softcom.aisentinel.domain.pii.reporting.FacetCount;
import pro.softcom.aisentinel.domain.pii.reporting.ScanReportingSummary;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;
import pro.softcom.aisentinel.domain.pii.reporting.SpaceSummary;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.DashboardFacetsDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.FacetCountDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanReportingSummaryDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SeverityCountsDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SpaceSummaryDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps domain {@link ScanReportingSummary} to its REST representation.
 *
 * <p>Per-type counts are read directly from the domain {@link SpaceSummary}, which the use case has
 * already populated. Severity counters are taken from the same scan-time aggregate and then
 * decremented by the space's false-positive occurrences, so findings reported as false positives
 * disappear from the dashboard counters just as they do from the item detail.
 */
@Component
@RequiredArgsConstructor
public class ScanReportingSummaryMapper {

    private final SeverityCountsMapper severityCountsMapper;
    private final DashboardFalsePositiveFilter falsePositiveFilter;

    public ScanReportingSummaryDto toDto(ScanReportingSummary summary) {
        if (summary == null) {
            return null;
        }

        List<SpaceSummaryDto> spaceDtos = summary.spaces() != null
                ? summary.spaces().stream().map(this::toSpaceDto).toList()
                : List.of();

        return new ScanReportingSummaryDto(
                summary.scanId(),
                summary.lastUpdated(),
                summary.spacesCount(),
                spaceDtos,
                toFacetsDto(summary.facets())
        );
    }

    private SpaceSummaryDto toSpaceDto(SpaceSummary space) {
        if (space == null) {
            return null;
        }

        SeverityCountsDto severityCountsDto = severityCountsMapper.toDto(correctedCounts(space));
        Map<String, Integer> piiTypeCounts = space.piiTypeCounts() != null ? space.piiTypeCounts() : Map.of();

        return new SpaceSummaryDto(
                space.spaceKey(),
                space.status(),
                space.progressPercentage(),
                space.pagesDone(),
                space.attachmentsDone(),
                space.lastEventAt(),
                severityCountsDto,
                space.spaceName(),
                piiTypeCounts,
                space.scanId()
        );
    }

    /**
     * Space severity counts minus its false-positive occurrences. When the space has no false
     * positive, the aggregate is returned untouched (possibly {@code null}, preserving the
     * "no counts recorded" contract), so the common case is unaffected.
     */
    private SeverityCounts correctedCounts(SpaceSummary space) {
        SeverityCounts base = space.severityCounts();
        SeverityCounts delta = falsePositiveFilter.falsePositiveDelta(space.scanId(), space.spaceKey());
        if (delta.total() == 0) {
            return base;
        }
        SeverityCounts nonNullBase = base == null ? SeverityCounts.zero() : base;
        return new SeverityCounts(
                Math.max(0, nonNullBase.high() - delta.high()),
                Math.max(0, nonNullBase.medium() - delta.medium()),
                Math.max(0, nonNullBase.low() - delta.low()));
    }

    private DashboardFacetsDto toFacetsDto(DashboardFacets facets) {
        if (facets == null) {
            return new DashboardFacetsDto(Map.of(), Map.of(), Map.of());
        }
        return new DashboardFacetsDto(
                toFacetCountMap(facets.piiTypes()),
                toFacetCountMap(facets.severities()),
                toFacetCountMap(facets.statuses())
        );
    }

    private Map<String, FacetCountDto> toFacetCountMap(Map<String, FacetCount> source) {
        if (source == null) {
            return Map.of();
        }
        Map<String, FacetCountDto> target = new LinkedHashMap<>();
        source.forEach((key, value) ->
                target.put(key, new FacetCountDto(value.spaceCount(), value.totalOccurrences())));
        return target;
    }
}
