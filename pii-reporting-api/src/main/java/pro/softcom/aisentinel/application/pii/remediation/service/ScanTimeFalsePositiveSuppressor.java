package pro.softcom.aisentinel.application.pii.remediation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.pii.remediation.port.out.FindingRemediationStore;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediation;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scan-time suppression of detections already flagged {@code FALSE_POSITIVE}.
 *
 * <p>Complements {@code FalsePositiveDetectionFilter}: the dashboard filter hides false positives
 * at read time (covering findings flagged after the last scan), whereas this suppressor drops them
 * at write time so a false positive known before a scan is never persisted nor counted again. The
 * scan events, the pre-aggregated severity counters, the per-space statistics and the live SSE view
 * all consume the reduced event, keeping every store consistent at the source rather than only in
 * the read model.</p>
 *
 * <p>Both operations are fail-open: a space is scanned without suppression if its remediation
 * projection cannot be read, and an event is kept unchanged if reduction throws, so false-positive
 * handling can never abort a scan.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class ScanTimeFalsePositiveSuppressor {

    private static final Set<FindingRemediationStatus> FALSE_POSITIVE_ONLY =
            Set.of(FindingRemediationStatus.FALSE_POSITIVE);

    private final FindingRemediationStore findingRemediationStore;
    private final ScanEventFindingResolver findingResolver;
    private final SeverityCalculationService severityCalculationService;

    /**
     * Stable finding ids flagged false positive for a space, loaded once so a whole space scan
     * reuses the same set. Returns an empty set when the space has no false-positive row or when
     * the projection cannot be read (fail-open).
     */
    public Set<String> falsePositiveIds(String spaceKey) {
        if (spaceKey == null || spaceKey.isBlank()) {
            return Set.of();
        }
        try {
            return findingRemediationStore.findBySpace(spaceKey, FALSE_POSITIVE_ONLY).stream()
                    .map(FindingRemediation::findingId)
                    .collect(Collectors.toSet());
        } catch (Exception failure) {
            log.warn("[SCAN_FP] Could not load false-positive findings for space {}: {} "
                    + "— scanning it without suppression", spaceKey, failure.getMessage());
            return Set.of();
        }
    }

    /**
     * Returns the event with false-positive detections removed and its per-item severity/type
     * summaries recomputed. The event is returned unchanged when it carries no detection, when
     * {@code falsePositiveIds} is empty, or when no detection matches. Fail-open on any failure.
     */
    public ConfluenceContentScanResult suppress(ConfluenceContentScanResult event,
                                                Set<String> falsePositiveIds) {
        if (event == null || falsePositiveIds == null || falsePositiveIds.isEmpty()) {
            return event;
        }
        List<DetectedPersonallyIdentifiableInformation> detections = event.detectedPIIs();
        if (detections == null || detections.isEmpty()) {
            return event;
        }
        try {
            List<DetectedPersonallyIdentifiableInformation> kept = detections.stream()
                    .filter(detection -> !isFalsePositive(event, detection, falsePositiveIds))
                    .toList();
            if (kept.size() == detections.size()) {
                return event;
            }
            return event.toBuilder()
                    .detectedPIIs(kept)
                    .detectedPiiCountBySeverity(severitySummary(kept))
                    .detectedPiiCountByType(typeSummary(kept))
                    .severity(highestSeverity(kept))
                    .build();
        } catch (Exception failure) {
            log.warn("[SCAN_FP] Could not apply false-positive suppression on space {} page {}: {} "
                    + "— keeping the event unchanged", event.spaceKey(), event.pageId(), failure.getMessage());
            return event;
        }
    }

    private boolean isFalsePositive(ConfluenceContentScanResult event,
                                    DetectedPersonallyIdentifiableInformation detection,
                                    Set<String> falsePositiveIds) {
        String findingId = findingResolver.stableFindingId(event, detection);
        return findingId != null && falsePositiveIds.contains(findingId);
    }

    private Map<String, Integer> severitySummary(List<DetectedPersonallyIdentifiableInformation> detections) {
        SeverityCounts counts = severityCalculationService.aggregateCounts(detections);
        return Map.of("high", counts.high(), "medium", counts.medium(), "low", counts.low());
    }

    private static Map<String, Integer> typeSummary(List<DetectedPersonallyIdentifiableInformation> detections) {
        return detections.stream()
                .filter(detection -> detection.piiType() != null)
                .collect(Collectors.groupingBy(
                        DetectedPersonallyIdentifiableInformation::piiType,
                        Collectors.summingInt(detection -> 1)));
    }

    private PersonallyIdentifiableInformationSeverity highestSeverity(
            List<DetectedPersonallyIdentifiableInformation> detections) {
        PersonallyIdentifiableInformationSeverity highest = null;
        for (DetectedPersonallyIdentifiableInformation detection : detections) {
            PersonallyIdentifiableInformationSeverity severity =
                    severityCalculationService.calculateSeverity(detection.piiType());
            if (highest == null || severity.compareTo(highest) < 0) {
                highest = severity;
            }
        }
        return highest;
    }
}
