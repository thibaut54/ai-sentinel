package pro.softcom.aisentinel.domain.pii.reporting;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.ScanStatus;

import java.util.List;
import java.util.Map;

@Builder(toBuilder = true)
public record ContentScanResult(
    String scanId,
    String sourceId, // Formerly spaceKey
    String eventType,
    Boolean isFinal,
    Integer contentTotal, // Formerly pagesTotal
    Integer contentIndex, // Formerly pageIndex
    String contentId, // Formerly pageId
    String contentTitle, // Formerly pageTitle
    List<DetectedPersonallyIdentifiableInformation> detectedPIIList,
    Map<String, Integer> nbOfDetectedPIIBySeverity,
    Map<String, Integer> nbOfDetectedPIIByType,
    @JsonIgnore String sourceContent,
    String maskedContent,
    String message,
    String contentUrl, // Formerly pageUrl
    String emittedAt,
    String attachmentName,
    String attachmentType,
    String attachmentUrl,
    Double analysisProgressPercentage,
    ScanStatus scanStatus,
    PersonallyIdentifiableInformationSeverity severity
) { }
