package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ConfluenceContentScanResultEventDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanEventType;

import java.util.List;

/**
 * Maps domain ScanResult (clean architecture) to presentation ScanEvent (DTO for SSE/JSON).
 * This keeps the domain independent from the web layer while preserving API contract.
 * 
 * <p>Security: sensitive values are ALWAYS masked in SSE events by delegating to the
 * domain method {@link DetectedPersonallyIdentifiableInformation#withMaskedSensitiveData()}.
 * This prevents accidental leaks via logs, monitoring, or network capture.</p>
 * 
 * <p>To reveal actual PII values, clients must use the dedicated /reveal endpoint
 * which respects the pii.reporting.allow-secret-reveal configuration.</p>
 */
@Component
@RequiredArgsConstructor
public class ConfluenceContentScanResultToScanEventMapper {

    public ConfluenceContentScanResultEventDto toDto(
        ContentScanResult result) {
        if (result == null) return null;
        
        // Delegate masking to domain business rule
        List<DetectedPersonallyIdentifiableInformation> detectedPIIs = result.detectedPIIList();
        if (detectedPIIs != null) {
            detectedPIIs = detectedPIIs.stream()
                    .map(DetectedPersonallyIdentifiableInformation::withMaskedSensitiveData)
                    .toList();
        }
        
        return ConfluenceContentScanResultEventDto.builder()
                .scanId(result.scanId())
                .spaceKey(result.sourceId())
                .eventType(ScanEventType.from(result.eventType()))
                .isFinal(result.isFinal())
                .pagesTotal(result.contentTotal())
                .pageIndex(result.contentIndex())
                .pageId(result.contentId())
                .pageTitle(result.contentTitle())
                .detectedPIIList(detectedPIIs)
                .nbOfDetectedPIIBySeverity(result.nbOfDetectedPIIBySeverity())
                .nbOfDetectedPIIByType(result.nbOfDetectedPIIByType())
                .message(result.message())
                .pageUrl(result.contentUrl())
                .emittedAt(result.emittedAt())
                .attachmentName(result.attachmentName())
                .attachmentType(result.attachmentType())
                .attachmentUrl(result.attachmentUrl())
                .analysisProgressPercentage(result.analysisProgressPercentage())
                .status(
                    result.scanStatus() != null ? result.scanStatus().name() : null)
                .severity(result.severity())
                .build();
    }
}
