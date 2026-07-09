package pro.softcom.aisentinel.application.pii.reporting.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.pii.remediation.port.out.FindingRemediationStore;
import pro.softcom.aisentinel.application.pii.remediation.service.ScanEventFindingResolver;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediation;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-time exclusion of findings flagged {@code FALSE_POSITIVE} from the Confluence dashboard.
 *
 * <p>The dashboard reporting read path is unaware of the remediation lifecycle: a finding reported
 * as a false positive in the obfuscation review must nevertheless disappear from the dashboard —
 * both from the per-item PII detail and from the severity counters. This service joins the reporting
 * scan events with the remediation projection at read time, using the same stable {@code findingId}
 * identity as the remediation context, and:
 * <ul>
 *   <li>{@link #excludeFalsePositives} strips false-positive detections from each item, recomputes
 *       the per-item severity/type summaries and drops items left with no detection;</li>
 *   <li>{@link #falsePositiveDelta} returns, per space, how many detections (occurrences) by
 *       severity were flagged false positive, so the pre-aggregated severity counters can be
 *       decremented to stay consistent with the filtered items.</li>
 * </ul>
 *
 * <p>Both operations short-circuit when a space has no false-positive row (the common case), so the
 * read path pays no extra cost unless false positives actually exist.</p>
 *
 * <p>The join is fail-open: if the remediation projection cannot be read, the affected space is left
 * unfiltered (and its counters undecremented) rather than hiding genuine PII from the dashboard.</p>
 */
@RequiredArgsConstructor
@Slf4j
public class DashboardFalsePositiveFilter {

    private static final Set<FindingRemediationStatus> FALSE_POSITIVE_ONLY =
            Set.of(FindingRemediationStatus.FALSE_POSITIVE);

    private final FindingRemediationStore findingRemediationStore;
    private final ScanEventFindingResolver findingResolver;
    private final SeverityCalculationService severityCalculationService;
    private final ScanResultQuery scanResultQuery;

    /**
     * Returns the given items with false-positive detections removed. Items belonging to a space
     * without any false-positive row are returned unchanged; items left with no detection are
     * dropped entirely.
     */
    public List<ConfluenceContentScanResult> excludeFalsePositives(List<ConfluenceContentScanResult> items) {
        if (items == null || items.isEmpty()) {
            return items == null ? List.of() : items;
        }
        Map<String, Set<String>> falsePositiveIdsBySpace = new HashMap<>();
        List<ConfluenceContentScanResult> result = new ArrayList<>(items.size());
        for (ConfluenceContentScanResult item : items) {
            Set<String> falsePositiveIds =
                    falsePositiveIdsBySpace.computeIfAbsent(item.spaceKey(), this::falsePositiveIds);
            ConfluenceContentScanResult filtered = excludeFrom(item, falsePositiveIds);
            if (filtered != null) {
                result.add(filtered);
            }
        }
        return result;
    }

    /**
     * Number of false-positive detections, by severity, for a space within a scan. Used to
     * decrement the pre-aggregated severity counters so they match the filtered item list. Returns
     * zero when the space has no false-positive row (or when the projection/events cannot be read).
     *
     * <p>Occurrences are bucketed by the severity <em>frozen on the finding's remediation row</em>,
     * not by re-deriving it at read time, so the decrement targets the same bucket the counter was
     * built from and is not perturbed by a later severity re-classification.</p>
     */
    public SeverityCounts falsePositiveDelta(String scanId, String spaceKey) {
        if (scanId == null || spaceKey == null) {
            return SeverityCounts.zero();
        }
        Map<String, PersonallyIdentifiableInformationSeverity> severityByFindingId =
                falsePositiveRows(spaceKey).stream()
                        .collect(Collectors.toMap(FindingRemediation::findingId, FindingRemediation::severity,
                                (first, second) -> first));
        if (severityByFindingId.isEmpty()) {
            return SeverityCounts.zero();
        }
        try {
            int high = 0;
            int medium = 0;
            int low = 0;
            for (ConfluenceContentScanResult event :
                    scanResultQuery.listItemEventsEncryptedByScanIdAndSpaceKey(scanId, spaceKey)) {
                for (DetectedPersonallyIdentifiableInformation detection : detectionsOf(event)) {
                    String findingId = findingResolver.stableFindingId(event, detection);
                    PersonallyIdentifiableInformationSeverity severity =
                            findingId == null ? null : severityByFindingId.get(findingId);
                    if (severity == null) {
                        continue;
                    }
                    switch (severity) {
                        case HIGH -> high++;
                        case MEDIUM -> medium++;
                        case LOW -> low++;
                    }
                }
            }
            return new SeverityCounts(high, medium, low);
        } catch (Exception failure) {
            log.warn("[DASHBOARD_FP] Could not compute false-positive counter delta for space {}: {} "
                    + "— leaving counters undecremented", spaceKey, failure.getMessage());
            return SeverityCounts.zero();
        }
    }

    private List<FindingRemediation> falsePositiveRows(String spaceKey) {
        try {
            return findingRemediationStore.findBySpace(spaceKey, FALSE_POSITIVE_ONLY);
        } catch (Exception failure) {
            log.warn("[DASHBOARD_FP] Could not load false-positive findings for space {}: {} "
                    + "— leaving it unfiltered", spaceKey, failure.getMessage());
            return List.of();
        }
    }

    private Set<String> falsePositiveIds(String spaceKey) {
        return falsePositiveRows(spaceKey).stream()
                .map(FindingRemediation::findingId)
                .collect(Collectors.toSet());
    }

    private ConfluenceContentScanResult excludeFrom(ConfluenceContentScanResult item,
                                                    Set<String> falsePositiveIds) {
        if (falsePositiveIds.isEmpty()) {
            return item;
        }
        List<DetectedPersonallyIdentifiableInformation> detections = detectionsOf(item);
        List<DetectedPersonallyIdentifiableInformation> kept = detections.stream()
                .filter(detection -> !isFalsePositive(item, detection, falsePositiveIds))
                .toList();
        if (kept.size() == detections.size()) {
            return item;
        }
        if (kept.isEmpty()) {
            return null;
        }
        return item.toBuilder()
                .detectedPIIList(kept)
                .nbOfDetectedPIIBySeverity(severitySummary(kept))
                .nbOfDetectedPIIByType(typeSummary(kept))
                .severity(highestSeverity(kept))
                .build();
    }

    private boolean isFalsePositive(ConfluenceContentScanResult event,
                                    DetectedPersonallyIdentifiableInformation detection,
                                    Set<String> falsePositiveIds) {
        String findingId = findingResolver.stableFindingId(event, detection);
        return findingId != null && falsePositiveIds.contains(findingId);
    }

    private static List<DetectedPersonallyIdentifiableInformation> detectionsOf(ConfluenceContentScanResult event) {
        return event.detectedPIIList() == null ? List.of() : event.detectedPIIList();
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
