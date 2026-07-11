package pro.softcom.aisentinel.application.pii.remediation.service;

import lombok.RequiredArgsConstructor;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.domain.pii.remediation.FindingReference;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves scan item events into remediation-eligible findings.
 *
 * <p>Detections without a {@code valueFingerprint} (persisted before fingerprinting was
 * introduced) have no stable identity and are counted as non-eligible instead. Multiple
 * occurrences of the same value on the same item share one identity and are collapsed
 * into a single finding, keeping the first occurrence's metadata and counting how many
 * raw detections it collapsed ({@code occurrenceCount}).</p>
 */
@RequiredArgsConstructor
public class ScanEventFindingResolver {

    private final SeverityCalculationService severityCalculationService;

    public Resolution resolve(List<ConfluenceContentScanResult> events) {
        Map<String, EligibleFinding> firstById = new LinkedHashMap<>();
        Map<String, Integer> occurrencesById = new HashMap<>();
        long nonEligibleLegacyCount = 0;
        for (ConfluenceContentScanResult event : events) {
            for (DetectedPersonallyIdentifiableInformation detection : detectionsOf(event)) {
                if (isLegacy(detection)) {
                    nonEligibleLegacyCount++;
                    continue;
                }
                EligibleFinding finding = toFinding(event, detection);
                firstById.putIfAbsent(finding.findingId(), finding);
                occurrencesById.merge(finding.findingId(), 1, Integer::sum);
            }
        }
        List<EligibleFinding> findings = firstById.values().stream()
                .map(finding -> finding.toBuilder()
                        .occurrenceCount(occurrencesById.get(finding.findingId()))
                        .build())
                .toList();
        return new Resolution(findings, nonEligibleLegacyCount);
    }

    private static List<DetectedPersonallyIdentifiableInformation> detectionsOf(ConfluenceContentScanResult event) {
        return event.detectedPIIList() == null ? List.of() : event.detectedPIIList();
    }

    private static boolean isLegacy(DetectedPersonallyIdentifiableInformation detection) {
        return detection.valueFingerprint() == null || detection.valueFingerprint().isBlank();
    }

    /**
     * Stable identity of a single detection as stored in the remediation projection, or
     * {@code null} when the detection has no well-formed stable identity. Exposed so read models
     * (e.g. the dashboard) can exclude findings by their remediation status without duplicating the
     * identity recipe.
     *
     * <p>Returns {@code null} both for legacy detections (no {@code valueFingerprint}) and for
     * detections that violate a {@link FindingReference} invariant (e.g. a blank {@code piiType} or
     * {@code detector} — states the scan pipeline tolerates but which cannot form a stable id). Such
     * a detection is therefore never treated as a tracked false positive, keeping the read path
     * robust rather than letting one borderline detection abort the whole dashboard aggregation.</p>
     */
    public String stableFindingId(ConfluenceContentScanResult event,
                                  DetectedPersonallyIdentifiableInformation detection) {
        if (isLegacy(detection)) {
            return null;
        }
        try {
            return referenceOf(event, detection).findingId();
        } catch (IllegalArgumentException identityViolation) {
            return null;
        }
    }

    private FindingReference referenceOf(ConfluenceContentScanResult event,
                                         DetectedPersonallyIdentifiableInformation detection) {
        return FindingReference.builder()
                .spaceKey(event.spaceKey())
                .pageId(event.pageId())
                .attachmentName(event.attachmentName())
                .detector(detectorOf(detection))
                .piiType(detection.piiType())
                .severity(severityCalculationService.calculateSeverity(detection.piiType()))
                .valueFingerprint(detection.valueFingerprint())
                .build();
    }

    private EligibleFinding toFinding(ConfluenceContentScanResult event,
                                      DetectedPersonallyIdentifiableInformation detection) {
        FindingReference reference = referenceOf(event, detection);
        return EligibleFinding.builder()
                .findingId(reference.findingId())
                .reference(reference)
                .confidence(detection.confidence())
                .piiTypeLabel(detection.piiTypeLabel())
                .maskedContext(detection.maskedContext())
                .sensitiveValue(detection.sensitiveValue())
                .sensitiveContext(detection.sensitiveContext())
                .pageTitle(event.pageTitle())
                .build();
    }

    private static String detectorOf(DetectedPersonallyIdentifiableInformation detection) {
        return detection.source() == null ? DetectorSource.UNKNOWN_SOURCE.name() : detection.source().name();
    }

    public record Resolution(List<EligibleFinding> findings, long nonEligibleLegacyCount) {
    }
}
