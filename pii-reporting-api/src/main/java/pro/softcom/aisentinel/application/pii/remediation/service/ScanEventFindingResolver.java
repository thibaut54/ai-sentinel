package pro.softcom.aisentinel.application.pii.remediation.service;

import lombok.RequiredArgsConstructor;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.domain.pii.remediation.FindingReference;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves scan item events into remediation-eligible findings.
 *
 * <p>Detections without a {@code valueFingerprint} (persisted before fingerprinting was
 * introduced) have no stable identity and are counted as non-eligible instead. Multiple
 * occurrences of the same value on the same item share one identity and are collapsed
 * into a single finding, keeping the first occurrence's metadata.</p>
 */
@RequiredArgsConstructor
public class ScanEventFindingResolver {

    private final SeverityCalculationService severityCalculationService;

    public Resolution resolve(List<ConfluenceContentScanResult> events) {
        Map<String, EligibleFinding> findingsById = new LinkedHashMap<>();
        long nonEligibleLegacyCount = 0;
        for (ConfluenceContentScanResult event : events) {
            for (DetectedPersonallyIdentifiableInformation detection : detectionsOf(event)) {
                if (isLegacy(detection)) {
                    nonEligibleLegacyCount++;
                    continue;
                }
                EligibleFinding finding = toFinding(event, detection);
                findingsById.putIfAbsent(finding.findingId(), finding);
            }
        }
        return new Resolution(List.copyOf(findingsById.values()), nonEligibleLegacyCount);
    }

    private static List<DetectedPersonallyIdentifiableInformation> detectionsOf(ConfluenceContentScanResult event) {
        return event.detectedPIIList() == null ? List.of() : event.detectedPIIList();
    }

    private static boolean isLegacy(DetectedPersonallyIdentifiableInformation detection) {
        return detection.valueFingerprint() == null || detection.valueFingerprint().isBlank();
    }

    private EligibleFinding toFinding(ConfluenceContentScanResult event,
                                      DetectedPersonallyIdentifiableInformation detection) {
        FindingReference reference = FindingReference.builder()
                .spaceKey(event.spaceKey())
                .pageId(event.pageId())
                .attachmentName(event.attachmentName())
                .detector(detectorOf(detection))
                .piiType(detection.piiType())
                .severity(severityCalculationService.calculateSeverity(detection.piiType()))
                .valueFingerprint(detection.valueFingerprint())
                .build();
        return EligibleFinding.builder()
                .findingId(reference.findingId())
                .reference(reference)
                .confidence(detection.confidence())
                .piiTypeLabel(detection.piiTypeLabel())
                .maskedContext(detection.maskedContext())
                .pageTitle(event.pageTitle())
                .build();
    }

    private static String detectorOf(DetectedPersonallyIdentifiableInformation detection) {
        return detection.source() == null ? DetectorSource.UNKNOWN_SOURCE.name() : detection.source().name();
    }

    public record Resolution(List<EligibleFinding> findings, long nonEligibleLegacyCount) {
    }
}
