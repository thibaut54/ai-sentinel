package pro.softcom.aisentinel.application.pii.remediation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.remediation.port.out.FindingRemediationStore;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScanTimeFalsePositiveSuppressor")
class ScanTimeFalsePositiveSuppressorTest {

    private static final String SCAN_ID = "scan-1";
    private static final String SPACE = "SPACE";

    private static final Map<String, PersonallyIdentifiableInformationSeverity> TYPE_SEVERITY = Map.of(
            "EMAIL", PersonallyIdentifiableInformationSeverity.LOW,
            "IBAN", PersonallyIdentifiableInformationSeverity.HIGH);

    @Mock
    private FindingRemediationStore findingRemediationStore;

    @Mock
    private SeverityCalculationService severityCalculationService;

    private ScanEventFindingResolver resolver;
    private ScanTimeFalsePositiveSuppressor suppressor;

    @BeforeEach
    void setUp() {
        resolver = new ScanEventFindingResolver(severityCalculationService);
        suppressor = new ScanTimeFalsePositiveSuppressor(findingRemediationStore, resolver, severityCalculationService);
        lenient().when(severityCalculationService.calculateSeverity(anyString()))
                .thenAnswer(invocation -> TYPE_SEVERITY.getOrDefault(
                        invocation.getArgument(0), PersonallyIdentifiableInformationSeverity.LOW));
        lenient().when(severityCalculationService.aggregateCounts(anyList()))
                .thenAnswer(invocation -> countBySeverity(invocation.getArgument(0)));
    }

    @Nested
    @DisplayName("falsePositiveIds")
    class FalsePositiveIds {

        @Test
        @DisplayName("Should_ReturnFindingIdsOfSpace_When_StoreHasFalsePositiveRows")
        void Should_ReturnFindingIdsOfSpace_When_StoreHasFalsePositiveRows() {
            when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                    .thenReturn(List.of(falsePositiveRow("fp-1"), falsePositiveRow("fp-2")));

            Set<String> ids = suppressor.falsePositiveIds(SPACE);

            assertThat(ids).containsExactlyInAnyOrder("fp-1", "fp-2");
        }

        @Test
        @DisplayName("Should_ReturnEmptySet_When_SpaceHasNoFalsePositiveRow")
        void Should_ReturnEmptySet_When_SpaceHasNoFalsePositiveRow() {
            when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                    .thenReturn(List.of());

            assertThat(suppressor.falsePositiveIds(SPACE)).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnEmptySetWithoutPropagatingException_When_StoreThrows")
        void Should_ReturnEmptySetWithoutPropagatingException_When_StoreThrows() {
            when(findingRemediationStore.findBySpace(SPACE, Set.of(FindingRemediationStatus.FALSE_POSITIVE)))
                    .thenThrow(new RuntimeException("remediation store unavailable"));

            assertThat(suppressor.falsePositiveIds(SPACE)).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnEmptySet_When_SpaceKeyIsBlank")
        void Should_ReturnEmptySet_When_SpaceKeyIsBlank() {
            assertThat(suppressor.falsePositiveIds(" ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("suppress")
    class Suppress {

        @Test
        @DisplayName("Should_RemoveFalsePositiveDetectionAndRecomputeSummaries_When_ItemMixesFalsePositiveAndGenuine")
        void Should_RemoveFalsePositiveDetectionAndRecomputeSummaries_When_ItemMixesFalsePositiveAndGenuine() {
            DetectedPersonallyIdentifiableInformation email = detection("EMAIL", "fp-email", DetectorSource.PRESIDIO);
            DetectedPersonallyIdentifiableInformation iban = detection("IBAN", "fp-iban", DetectorSource.REGEX);
            ConfluenceContentScanResult event = pageEvent(List.of(email, iban));
            String emailFindingId = resolver.stableFindingId(event, email);

            ConfluenceContentScanResult suppressed = suppressor.suppress(event, Set.of(emailFindingId));

            assertSoftly(softly -> {
                softly.assertThat(suppressed.detectedPIIs()).containsExactly(iban);
                softly.assertThat(suppressed.detectedPiiCountBySeverity())
                        .isEqualTo(Map.of("high", 1, "medium", 0, "low", 0));
                softly.assertThat(suppressed.detectedPiiCountByType()).isEqualTo(Map.of("IBAN", 1));
                softly.assertThat(suppressed.severity()).isEqualTo(PersonallyIdentifiableInformationSeverity.HIGH);
            });
        }

        @Test
        @DisplayName("Should_ReturnEventUnchanged_When_FalsePositiveIdsIsEmpty")
        void Should_ReturnEventUnchanged_When_FalsePositiveIdsIsEmpty() {
            ConfluenceContentScanResult event = pageEvent(
                    List.of(detection("EMAIL", "fp-email", DetectorSource.PRESIDIO)));

            assertThat(suppressor.suppress(event, Set.of())).isSameAs(event);
        }

        @Test
        @DisplayName("Should_ReturnEventUnchanged_When_EventHasNoDetections")
        void Should_ReturnEventUnchanged_When_EventHasNoDetections() {
            ConfluenceContentScanResult event = pageEvent(List.of());

            assertThat(suppressor.suppress(event, Set.of("some-id"))).isSameAs(event);
        }

        @Test
        @DisplayName("Should_ReturnEventUnchanged_When_NoDetectionMatchesFalsePositiveIds")
        void Should_ReturnEventUnchanged_When_NoDetectionMatchesFalsePositiveIds() {
            ConfluenceContentScanResult event = pageEvent(
                    List.of(detection("EMAIL", "fp-email", DetectorSource.PRESIDIO)));

            assertThat(suppressor.suppress(event, Set.of("some-other-id"))).isSameAs(event);
        }

        @Test
        @DisplayName("Should_ReturnEmptyDetectionsAndZeroCounters_When_AllDetectionsAreFalsePositive")
        void Should_ReturnEmptyDetectionsAndZeroCounters_When_AllDetectionsAreFalsePositive() {
            DetectedPersonallyIdentifiableInformation email = detection("EMAIL", "fp-email", DetectorSource.PRESIDIO);
            ConfluenceContentScanResult event = pageEvent(List.of(email));
            String emailFindingId = resolver.stableFindingId(event, email);

            ConfluenceContentScanResult suppressed = suppressor.suppress(event, Set.of(emailFindingId));

            assertSoftly(softly -> {
                softly.assertThat(suppressed.detectedPIIs()).isEmpty();
                softly.assertThat(suppressed.detectedPiiCountBySeverity())
                        .isEqualTo(Map.of("high", 0, "medium", 0, "low", 0));
                softly.assertThat(suppressed.detectedPiiCountByType()).isEmpty();
                softly.assertThat(suppressed.severity()).isNull();
            });
        }

        @Test
        @DisplayName("Should_SuppressDetection_When_ProducedByDifferentDetectorThanTheOneMarkedFalsePositive")
        void Should_SuppressDetection_When_ProducedByDifferentDetectorThanTheOneMarkedFalsePositive() {
            // The false positive was recorded against a PRESIDIO detection...
            DetectedPersonallyIdentifiableInformation flaggedByPresidio =
                    detection("EMAIL", "fp-email", DetectorSource.PRESIDIO);
            ConfluenceContentScanResult flaggingEvent = pageEvent(List.of(flaggedByPresidio));
            String falsePositiveId = resolver.stableFindingId(flaggingEvent, flaggedByPresidio);

            // ...but a later scan re-surfaces the very same value at the very same location
            // through a different detector. The identity is value/location-based, not
            // detector-based, so it must still be suppressed.
            DetectedPersonallyIdentifiableInformation reDetectedByRegex =
                    detection("EMAIL", "fp-email", DetectorSource.REGEX);
            ConfluenceContentScanResult rescanEvent = pageEvent(List.of(reDetectedByRegex));

            ConfluenceContentScanResult suppressed = suppressor.suppress(rescanEvent, Set.of(falsePositiveId));

            assertThat(suppressed.detectedPIIs()).isEmpty();
        }

        @Test
        @DisplayName("Should_KeepLegacyDetection_When_ValueFingerprintIsMissing")
        void Should_KeepLegacyDetection_When_ValueFingerprintIsMissing() {
            DetectedPersonallyIdentifiableInformation legacy = detection("EMAIL", null, DetectorSource.PRESIDIO);
            ConfluenceContentScanResult event = pageEvent(List.of(legacy));
            // A stale FALSE_POSITIVE row must never remove a legacy detection (no stable identity).
            Set<String> falsePositiveIds = Set.of("some-other-id");

            assertThat(suppressor.suppress(event, falsePositiveIds)).isSameAs(event);
        }

        @Test
        @DisplayName("Should_ReturnNull_When_EventIsNull")
        void Should_ReturnNull_When_EventIsNull() {
            assertThat(suppressor.suppress(null, Set.of("some-id"))).isNull();
        }

        @Test
        @DisplayName("Should_ReturnEventUnchanged_When_SummaryRecomputationThrows")
        void Should_ReturnEventUnchanged_When_SummaryRecomputationThrows() {
            DetectedPersonallyIdentifiableInformation email = detection("EMAIL", "fp-email", DetectorSource.PRESIDIO);
            DetectedPersonallyIdentifiableInformation iban = detection("IBAN", "fp-iban", DetectorSource.REGEX);
            ConfluenceContentScanResult event = pageEvent(List.of(email, iban));
            String emailFindingId = resolver.stableFindingId(event, email);
            when(severityCalculationService.aggregateCounts(anyList()))
                    .thenThrow(new RuntimeException("severity aggregation failure"));

            assertThat(suppressor.suppress(event, Set.of(emailFindingId))).isSameAs(event);
        }
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

    private static ConfluenceContentScanResult pageEvent(List<DetectedPersonallyIdentifiableInformation> detections) {
        return ConfluenceContentScanResult.builder()
                .scanId(SCAN_ID)
                .spaceKey(SPACE)
                .eventType("item")
                .pageId("p1")
                .pageTitle("Title " + "p1")
                .detectedPIIs(detections)
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
        return FindingRemediation.builder()
                .findingId(findingId)
                .scanId(SCAN_ID)
                .spaceKey(SPACE)
                .pageId("p1")
                .piiType("EMAIL")
                .severity(PersonallyIdentifiableInformationSeverity.LOW)
                .detector("PRESIDIO")
                .status(FindingRemediationStatus.FALSE_POSITIVE)
                .actor("reviewer")
                .occurredAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }
}
