package pro.softcom.aisentinel.application.pii.reporting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardFalsePositiveFilter")
class DashboardFalsePositiveFilterTest {

    private static final String SCAN_ID = "scan-1";
    private static final String SPACE = "SPACE";

    private static final Map<String, PersonallyIdentifiableInformationSeverity> TYPE_SEVERITY = Map.of(
            "EMAIL", PersonallyIdentifiableInformationSeverity.LOW,
            "IBAN", PersonallyIdentifiableInformationSeverity.HIGH,
            "PASSPORT_NUMBER", PersonallyIdentifiableInformationSeverity.MEDIUM);

    @Mock
    private FindingRemediationStore findingRemediationStore;

    @Mock
    private SeverityCalculationService severityCalculationService;

    @Mock
    private ScanResultQuery scanResultQuery;

    private ScanEventFindingResolver resolver;
    private DashboardFalsePositiveFilter filter;

    @BeforeEach
    void setUp() {
        resolver = new ScanEventFindingResolver(severityCalculationService);
        filter = new DashboardFalsePositiveFilter(findingRemediationStore, resolver,
                severityCalculationService, scanResultQuery);
        lenient().when(severityCalculationService.calculateSeverity(anyString()))
                .thenAnswer(invocation -> TYPE_SEVERITY.getOrDefault(
                        invocation.getArgument(0), PersonallyIdentifiableInformationSeverity.LOW));
        lenient().when(severityCalculationService.aggregateCounts(anyList()))
                .thenAnswer(invocation -> countBySeverity(invocation.getArgument(0)));
    }

    @Test
    @DisplayName("Should_RemoveFalsePositiveDetectionsAndRecomputeSummaries_When_ItemMixesFalsePositiveAndGenuine")
    void Should_RemoveFalsePositiveDetectionsAndRecomputeSummaries_When_ItemMixesFalsePositiveAndGenuine() {
        DetectedPersonallyIdentifiableInformation email1 = detection("EMAIL", "fp-email", DetectorSource.PRESIDIO);
        DetectedPersonallyIdentifiableInformation email2 = detection("EMAIL", "fp-email", DetectorSource.PRESIDIO);
        DetectedPersonallyIdentifiableInformation iban = detection("IBAN", "fp-iban", DetectorSource.REGEX);
        ConfluenceContentScanResult page = pageEvent("p1", List.of(email1, email2, iban));
        String emailFindingId = resolver.stableFindingId(page, email1);
        when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                .thenReturn(List.of(falsePositiveRow(emailFindingId)));

        List<ConfluenceContentScanResult> result = filter.excludeFalsePositives(List.of(page));

        assertThat(result).hasSize(1);
        ConfluenceContentScanResult filtered = result.getFirst();
        assertSoftly(softly -> {
            softly.assertThat(filtered.detectedPIIs()).containsExactly(iban);
            softly.assertThat(filtered.detectedPiiCountBySeverity())
                    .isEqualTo(Map.of("high", 1, "medium", 0, "low", 0));
            softly.assertThat(filtered.detectedPiiCountByType()).isEqualTo(Map.of("IBAN", 1));
            softly.assertThat(filtered.severity()).isEqualTo(PersonallyIdentifiableInformationSeverity.HIGH);
        });
    }

    @Test
    @DisplayName("Should_DropItem_When_AllDetectionsAreFalsePositive")
    void Should_DropItem_When_AllDetectionsAreFalsePositive() {
        DetectedPersonallyIdentifiableInformation email = detection("EMAIL", "fp-email", DetectorSource.PRESIDIO);
        ConfluenceContentScanResult page = pageEvent("p1", List.of(email));
        String emailFindingId = resolver.stableFindingId(page, email);
        when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                .thenReturn(List.of(falsePositiveRow(emailFindingId)));

        assertThat(filter.excludeFalsePositives(List.of(page))).isEmpty();
    }

    @Test
    @DisplayName("Should_ReturnItemsUnchanged_When_SpaceHasNoFalsePositive")
    void Should_ReturnItemsUnchanged_When_SpaceHasNoFalsePositive() {
        ConfluenceContentScanResult page = pageEvent("p1",
                List.of(detection("EMAIL", "fp-email", DetectorSource.PRESIDIO)));
        when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                .thenReturn(List.of());

        List<ConfluenceContentScanResult> result = filter.excludeFalsePositives(List.of(page));

        assertThat(result).containsExactly(page);
    }

    @Test
    @DisplayName("Should_KeepLegacyDetection_When_FingerprintMissing")
    void Should_KeepLegacyDetection_When_FingerprintMissing() {
        DetectedPersonallyIdentifiableInformation legacy = detection("EMAIL", null, DetectorSource.PRESIDIO);
        ConfluenceContentScanResult page = pageEvent("p1", List.of(legacy));
        // A stale FALSE_POSITIVE row must never remove a legacy detection (no stable identity).
        when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                .thenReturn(List.of(falsePositiveRow("some-other-id")));

        assertThat(filter.excludeFalsePositives(List.of(page))).containsExactly(page);
    }

    @Test
    @DisplayName("Should_CountFalsePositiveOccurrencesBySeverity_When_ComputingDelta")
    void Should_CountFalsePositiveOccurrencesBySeverity_When_ComputingDelta() {
        DetectedPersonallyIdentifiableInformation email1 = detection("EMAIL", "fp-email", DetectorSource.PRESIDIO);
        DetectedPersonallyIdentifiableInformation email2 = detection("EMAIL", "fp-email", DetectorSource.PRESIDIO);
        DetectedPersonallyIdentifiableInformation iban = detection("IBAN", "fp-iban", DetectorSource.REGEX);
        ConfluenceContentScanResult page = pageEvent("p1", List.of(email1, email2, iban));
        String emailFindingId = resolver.stableFindingId(page, email1);
        when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                .thenReturn(List.of(falsePositiveRow(emailFindingId)));
        when(scanResultQuery.listItemEventsEncryptedByScanIdAndSpaceKey(SCAN_ID, SPACE))
                .thenReturn(List.of(page));

        SeverityCounts delta = filter.falsePositiveDelta(SCAN_ID, SPACE);

        // Both EMAIL occurrences (LOW) are counted; the genuine IBAN is not.
        assertThat(delta).isEqualTo(new SeverityCounts(0, 0, 2));
    }

    @Test
    @DisplayName("Should_ReturnZeroDeltaWithoutReadingEvents_When_SpaceHasNoFalsePositive")
    void Should_ReturnZeroDeltaWithoutReadingEvents_When_SpaceHasNoFalsePositive() {
        when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                .thenReturn(List.of());

        SeverityCounts delta = filter.falsePositiveDelta(SCAN_ID, SPACE);

        assertThat(delta).isEqualTo(SeverityCounts.zero());
        verify(scanResultQuery, never()).listItemEventsEncryptedByScanIdAndSpaceKey(any(), any());
    }

    @Test
    @DisplayName("Should_BucketDeltaByFrozenRowSeverity_When_RowSeverityDiffersFromType")
    void Should_BucketDeltaByFrozenRowSeverity_When_RowSeverityDiffersFromType() {
        DetectedPersonallyIdentifiableInformation email = detection("EMAIL", "fp-email", DetectorSource.PRESIDIO);
        ConfluenceContentScanResult page = pageEvent("p1", List.of(email));
        String emailFindingId = resolver.stableFindingId(page, email);
        // EMAIL re-derives to LOW, but the finding was frozen HIGH on its remediation row.
        when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                .thenReturn(List.of(falsePositiveRow(emailFindingId, PersonallyIdentifiableInformationSeverity.HIGH)));
        when(scanResultQuery.listItemEventsEncryptedByScanIdAndSpaceKey(SCAN_ID, SPACE))
                .thenReturn(List.of(page));

        SeverityCounts delta = filter.falsePositiveDelta(SCAN_ID, SPACE);

        assertThat(delta).isEqualTo(new SeverityCounts(1, 0, 0));
    }

    @Test
    @DisplayName("Should_LeaveSpaceUnfilteredAndZeroDelta_When_StoreThrows")
    void Should_LeaveSpaceUnfilteredAndZeroDelta_When_StoreThrows() {
        ConfluenceContentScanResult page = pageEvent("p1",
                List.of(detection("EMAIL", "fp-email", DetectorSource.PRESIDIO)));
        when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                .thenThrow(new RuntimeException("remediation store unavailable"));

        assertSoftly(softly -> {
            softly.assertThat(filter.excludeFalsePositives(List.of(page))).containsExactly(page);
            softly.assertThat(filter.falsePositiveDelta(SCAN_ID, SPACE)).isEqualTo(SeverityCounts.zero());
        });
    }

    @Test
    @DisplayName("Should_KeepDetectionWithBlankPiiType_When_Filtering")
    void Should_KeepDetectionWithBlankPiiType_When_Filtering() {
        // Non-legacy (fingerprint set) but invalid identity (blank type): kept, never crashes.
        ConfluenceContentScanResult page = pageEvent("p1",
                List.of(detection(" ", "fp-blank", DetectorSource.PRESIDIO)));
        when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                .thenReturn(List.of(falsePositiveRow("some-other-id")));

        assertThat(filter.excludeFalsePositives(List.of(page))).containsExactly(page);
    }

    @Test
    @DisplayName("Should_LoadFalsePositiveRowsOncePerSpace_When_FilteringMultipleSpaces")
    void Should_LoadFalsePositiveRowsOncePerSpace_When_FilteringMultipleSpaces() {
        ConfluenceContentScanResult pageA1 = pageEvent("SPACE-A", "p1",
                List.of(detection("EMAIL", "fp-a1", DetectorSource.PRESIDIO)));
        ConfluenceContentScanResult pageA2 = pageEvent("SPACE-A", "p2",
                List.of(detection("EMAIL", "fp-a2", DetectorSource.PRESIDIO)));
        ConfluenceContentScanResult pageB = pageEvent("SPACE-B", "p3",
                List.of(detection("EMAIL", "fp-b", DetectorSource.PRESIDIO)));
        when(findingRemediationStore.findBySpace(anyString(), eq(Set.of(FindingRemediationStatus.FALSE_POSITIVE))))
                .thenReturn(List.of());

        filter.excludeFalsePositives(List.of(pageA1, pageA2, pageB));

        verify(findingRemediationStore, times(1))
                .findBySpace("SPACE-A", Set.of(FindingRemediationStatus.FALSE_POSITIVE));
        verify(findingRemediationStore, times(1))
                .findBySpace("SPACE-B", Set.of(FindingRemediationStatus.FALSE_POSITIVE));
    }

    private static SeverityCounts countBySeverity(List<DetectedPersonallyIdentifiableInformation> detections) {
        int high = 0;
        int medium = 0;
        int low = 0;
        for (DetectedPersonallyIdentifiableInformation detection : detections) {
            switch (TYPE_SEVERITY.getOrDefault(detection.piiType(), PersonallyIdentifiableInformationSeverity.LOW)) {
                case HIGH -> high++;
                case MEDIUM -> medium++;
                case LOW -> low++;
            }
        }
        return new SeverityCounts(high, medium, low);
    }

    private static ConfluenceContentScanResult pageEvent(String pageId,
                                                         List<DetectedPersonallyIdentifiableInformation> detections) {
        return pageEvent(SPACE, pageId, detections);
    }

    private static ConfluenceContentScanResult pageEvent(String spaceKey, String pageId,
                                                         List<DetectedPersonallyIdentifiableInformation> detections) {
        SeverityCounts counts = countBySeverity(detections);
        return ConfluenceContentScanResult.builder()
                .scanId(SCAN_ID)
                .spaceKey(spaceKey)
                .eventType("item")
                .pageId(pageId)
                .pageTitle("Title " + pageId)
                .detectedPIIs(detections)
                .detectedPiiCountBySeverity(counts.total() == 0 ? Map.of()
                        : Map.of("high", counts.high(), "medium", counts.medium(), "low", counts.low()))
                .build();
    }

    private static DetectedPersonallyIdentifiableInformation detection(String piiType, String fingerprint,
                                                                       DetectorSource source) {
        return DetectedPersonallyIdentifiableInformation.builder()
                .startPosition(0)
                .endPosition(10)
                .piiType(piiType)
                .piiTypeLabel(piiType)
                .confidence(0.9)
                .maskedContext("masked-" + piiType)
                .source(source)
                .valueFingerprint(fingerprint)
                .build();
    }

    private static FindingRemediation falsePositiveRow(String findingId) {
        return falsePositiveRow(findingId, PersonallyIdentifiableInformationSeverity.LOW);
    }

    private static FindingRemediation falsePositiveRow(String findingId,
                                                       PersonallyIdentifiableInformationSeverity severity) {
        return FindingRemediation.builder()
                .findingId(findingId)
                .scanId(SCAN_ID)
                .spaceKey(SPACE)
                .pageId("p1")
                .piiType("EMAIL")
                .severity(severity)
                .detector("PRESIDIO")
                .status(FindingRemediationStatus.FALSE_POSITIVE)
                .actor("reviewer")
                .occurredAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
