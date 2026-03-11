package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.reporting.ScanSeverityCountService;
import pro.softcom.aisentinel.domain.pii.reporting.ScanReportingSummary;
import pro.softcom.aisentinel.domain.pii.reporting.ScanSeverityCount;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;
import pro.softcom.aisentinel.domain.pii.reporting.SpaceSummary;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanReportingSummaryDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SeverityCountsDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SpaceSummaryDto;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Maps domain DashboardSummary to presentation DashboardSummaryDto.
 * This keeps the domain independent from the web layer while preserving API contract.
 */
@Component
@RequiredArgsConstructor
public class ScanReportingSummaryMapper {

    private final ScanSeverityCountService severityCountService;
    private final SeverityCountsMapper severityCountsMapper;

    public ScanReportingSummaryDto toDto(ScanReportingSummary summary) {
        if (summary == null) {
            return null;
        }

        String scanId = summary.scanId();

        // Load all severity counts for this scan once, indexed by sourceKey
        Map<String, SeverityCounts> countsBySourceKey = severityCountService.getCountsByScan(scanId).stream()
                .collect(Collectors.toMap(ScanSeverityCount::sourceKey, ScanSeverityCount::counts, (a, b) -> a));

        List<SpaceSummaryDto> spaceDtos = summary.spaces() != null
                ? summary.spaces().stream()
                    .map(space -> toSpaceDto(space, countsBySourceKey))
                    .toList()
                : List.of();

        return new ScanReportingSummaryDto(
                summary.scanId(),
                summary.lastUpdated(),
                summary.spacesCount(),
                spaceDtos
        );
    }

    private SpaceSummaryDto toSpaceDto(SpaceSummary space, Map<String, SeverityCounts> countsBySourceKey) {
        if (space == null) {
            return null;
        }

        SeverityCounts counts = countsBySourceKey.get(space.sourceKey());
        SeverityCountsDto severityCountsDto = severityCountsMapper.toDto(counts);

        return new SpaceSummaryDto(
                space.sourceKey(),
                space.status().name(),
                space.progressPercentage(),
                space.pagesDone(),
                space.attachmentsDone(),
                space.lastEventTs(),
                severityCountsDto
        );
    }
}
