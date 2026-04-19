package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.reporting.ScanSeverityCountService;
import pro.softcom.aisentinel.domain.pii.reporting.ScanReportingSummary;
import pro.softcom.aisentinel.domain.pii.reporting.ScanSeverityCount;
import pro.softcom.aisentinel.domain.pii.reporting.SpaceSummary;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ClassificationCountsDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanReportingSummaryDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SeverityCountsDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SpaceSummaryDto;

import java.util.List;
import java.util.Optional;

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

        Optional<ScanSeverityCount> scanCountOpt = severityCountService.getScanCount(scanId, space.spaceKey());
        SeverityCountsDto severityCountsDto = severityCountsMapper.toDto(
                scanCountOpt.map(ScanSeverityCount::counts).orElse(null));
        ClassificationCountsDto classificationCountsDto = severityCountsMapper.toClassificationDto(
                scanCountOpt.map(ScanSeverityCount::classificationCounts).orElse(null));

        return new SpaceSummaryDto(
                space.spaceKey(),
                space.status(),
                space.progressPercentage(),
                space.pagesDone(),
                space.attachmentsDone(),
                space.lastEventTs(),
                severityCountsDto,
                classificationCountsDto
        );
    }
}
