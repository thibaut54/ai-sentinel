package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto;

import java.time.Instant;
import java.util.Map;

public record SpaceSummaryDto(
        String spaceKey,
        String status,
        Double progressPercentage,
        long pagesDone,
        long attachmentsDone,
        Instant lastEventTs,
        SeverityCountsDto severityCounts,
        String spaceName,
        Map<String, Integer> piiTypeCounts,
        String scanId
) { }
