package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.reporting.ScanSeverityCountService;
import pro.softcom.aisentinel.application.pii.reporting.service.DashboardFalsePositiveFilter;
import pro.softcom.aisentinel.domain.pii.reporting.ScanReportingSummary;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;
import pro.softcom.aisentinel.domain.pii.reporting.SpaceSummary;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanReportingSummaryDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SeverityCountsDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SpaceSummaryDto;

import java.util.List;

/**
 * Maps domain DashboardSummary to presentation DashboardSummaryDto.
 * This keeps the domain independent from the web layer while preserving API contract.
 *
 * <p>Severity counters are read from the scan-time aggregate and then decremented by the
 * false-positive occurrences of the space, so findings reported as false positives disappear
 * from the dashboard counters just as they do from the item detail.</p>
 */
@Component
@RequiredArgsConstructor
public class ScanReportingSummaryMapper {

    private final ScanSeverityCountService severityCountService;
    private final SeverityCountsMapper severityCountsMapper;
    private final DashboardFalsePositiveFilter falsePositiveFilter;

    public ScanReportingSummaryDto toDto(ScanReportingSummary summary) {
        if (summary == null) {
            return null;
        }
        
        String scanId = summary.scanId();
        List<SpaceSummaryDto> spaceDtos = summary.spaces() != null
                ? summary.spaces().stream()
                    .map(space -> toSpaceDto(scanId, space))
                    .toList()
                : List.of();
        
        return new ScanReportingSummaryDto(
                summary.scanId(),
                summary.lastUpdated(),
                summary.spacesCount(),
                spaceDtos
        );
    }

    private SpaceSummaryDto toSpaceDto(String scanId, SpaceSummary space) {
        if (space == null) {
            return null;
        }

        SeverityCounts counts = correctedCounts(scanId, space.spaceKey());
        SeverityCountsDto severityCountsDto = severityCountsMapper.toDto(counts);

        return new SpaceSummaryDto(
                space.spaceKey(),
                space.status(),
                space.progressPercentage(),
                space.pagesDone(),
                space.attachmentsDone(),
                space.lastEventTs(),
                severityCountsDto
        );
    }

    /**
     * Aggregated severity counts of a space minus its false-positive occurrences. When the space
     * has no false positive, the aggregate is returned untouched (possibly {@code null}, preserving
     * the "no counts recorded" contract), so the common case is unaffected.
     */
    private SeverityCounts correctedCounts(String scanId, String spaceKey) {
        SeverityCounts base = severityCountService.getCounts(scanId, spaceKey).orElse(null);
        SeverityCounts delta = falsePositiveFilter.falsePositiveDelta(scanId, spaceKey);
        if (delta.total() == 0) {
            return base;
        }
        SeverityCounts nonNullBase = base == null ? SeverityCounts.zero() : base;
        return new SeverityCounts(
                Math.max(0, nonNullBase.high() - delta.high()),
                Math.max(0, nonNullBase.medium() - delta.medium()),
                Math.max(0, nonNullBase.low() - delta.low()));
    }
}
