package pro.softcom.aisentinel.application.pii.export;

import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.pii.export.dto.DetectionReportEntry;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;

import java.util.List;

@Slf4j
public class DetectionReportMapper {
    public List<DetectionReportEntry> toDetectionReportEntries(
        ContentScanResult result) {
        if (!isValidResult(result)) {
            return List.of();
        }

        return result.detectedPIIList().stream()
                .map(piiEntity -> mapPiiEntityToDetectionReportEntry(piiEntity,
                                                                     result))
                .toList();
    }

    private boolean isValidResult(ContentScanResult result) {
        return result != null && result.detectedPIIList() != null && !result.detectedPIIList().isEmpty();
    }

    private DetectionReportEntry mapPiiEntityToDetectionReportEntry(
        DetectedPersonallyIdentifiableInformation detectedPersonallyIdentifiableInformation, ContentScanResult result) {
        return DetectionReportEntry.builder()
                .scanId(result.scanId())
                .spaceKey(result.sourceId())
                .emittedAt(result.emittedAt())
                .pageTitle(result.contentTitle())
                .pageUrl(result.contentUrl())
                .attachmentName(result.attachmentName())
                .attachmentUrl(result.attachmentUrl())
                .maskedContext(detectedPersonallyIdentifiableInformation.maskedContext())
                .type(detectedPersonallyIdentifiableInformation.piiType())
                .typeLabel(detectedPersonallyIdentifiableInformation.piiTypeLabel())
                .confidenceScore(detectedPersonallyIdentifiableInformation.confidence())
                .build();
    }
}
