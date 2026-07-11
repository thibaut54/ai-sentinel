package pro.softcom.aisentinel.application.pii.remediation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.remediation.port.out.FindingRemediationStore;
import pro.softcom.aisentinel.application.pii.remediation.service.SelectionResolver.ResolvedSelection;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.domain.pii.reporting.LastScanMeta;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SelectionResolver")
class SelectionResolverTest {

    private static final String SCAN_ID = "scan-1";
    private static final String SPACE_KEY = "SPACE";

    @Mock
    private ScanResultQuery scanResultQuery;

    @Mock
    private FindingRemediationStore findingRemediationStore;

    @Mock
    private SeverityCalculationService severityCalculationService;

    private SelectionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SelectionResolver(scanResultQuery, findingRemediationStore,
                new ScanEventFindingResolver(severityCalculationService), new SelectionEvaluator());
        lenient().when(severityCalculationService.calculateSeverity(anyString()))
                .thenReturn(PersonallyIdentifiableInformationSeverity.MEDIUM);
        lenient().when(scanResultQuery.findLatestScan())
                .thenReturn(Optional.of(new LastScanMeta(SCAN_ID, Instant.parse("2026-01-01T10:00:00Z"), 1)));
        lenient().when(findingRemediationStore.findStatusesByIds(any())).thenReturn(Map.of());
    }

    @Test
    @DisplayName("Should_ReturnEmptyResolution_When_NoScanExists")
    void Should_ReturnEmptyResolution_When_NoScanExists() {
        when(scanResultQuery.findLatestScan()).thenReturn(Optional.empty());

        ResolvedSelection resolved = resolver.resolve(selectionOfTypes("EMAIL"));

        assertSoftly(softly -> {
            softly.assertThat(resolved.scanId()).isNull();
            softly.assertThat(resolved.pageFindings()).isEmpty();
            softly.assertThat(resolved.attachmentFindings()).isEmpty();
            softly.assertThat(resolved.falsePositivesReported()).isZero();
        });
    }

    @Test
    @DisplayName("Should_SplitPageAndAttachmentFindings_When_SelectionMatchesBoth")
    void Should_SplitPageAndAttachmentFindings_When_SelectionMatchesBoth() {
        stubEvents(
                event("p1", null, detection("EMAIL", "fp-1")),
                event("p1", "report.xlsx", detection("EMAIL", "fp-2")));

        ResolvedSelection resolved = resolver.resolve(selectionOfTypes("EMAIL"));

        assertSoftly(softly -> {
            softly.assertThat(resolved.scanId()).isEqualTo(SCAN_ID);
            softly.assertThat(resolved.pageFindings()).hasSize(1);
            softly.assertThat(resolved.pageFindings().getFirst().reference().attachmentName()).isNull();
            softly.assertThat(resolved.attachmentFindings()).hasSize(1);
            softly.assertThat(resolved.attachmentFindings().getFirst().reference().attachmentName())
                    .isEqualTo("report.xlsx");
        });
    }

    @Test
    @DisplayName("Should_ResolveOnlyPendingFindings_When_SomeFindingsHaveNonPendingStatus")
    void Should_ResolveOnlyPendingFindings_When_SomeFindingsHaveNonPendingStatus() {
        stubEvents(event("p1", null, detection("EMAIL", "fp-1"), detection("EMAIL", "fp-2")));
        when(findingRemediationStore.findStatusesByIds(any())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            return Map.of(ids.getFirst(), FindingRemediationStatus.FALSE_POSITIVE);
        });

        ResolvedSelection resolved = resolver.resolve(selectionOfTypes("EMAIL"));

        assertSoftly(softly -> {
            softly.assertThat(resolved.pageFindings()).hasSize(1);
            softly.assertThat(resolved.pageFindings().getFirst().reference().valueFingerprint())
                    .isEqualTo("fp-2");
            softly.assertThat(resolved.falsePositivesReported()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("Should_ApplyExclusionsAndInclusions_When_SelectionRefinedPerFinding")
    void Should_ApplyExclusionsAndInclusions_When_SelectionRefinedPerFinding() {
        stubEvents(event("p1", null, detection("EMAIL", "fp-1"), detection("PHONE", "fp-2")));
        ResolvedSelection all = resolver.resolve(selectionOfTypes("EMAIL", "PHONE"));
        String emailId = all.pageFindings().getFirst().findingId();
        String phoneId = all.pageFindings().getLast().findingId();

        ResolvedSelection refined = resolver.resolve(RemediationSelection.builder()
                .spaceKey(SPACE_KEY)
                .piiTypes(List.of("EMAIL"))
                .excludedFindingIds(Set.of(emailId))
                .includedFindingIds(Set.of(phoneId))
                .build());

        assertThat(refined.pageFindingIds()).containsExactly(phoneId);
    }

    @Test
    @DisplayName("Should_RestrictToPage_When_ScopeHasPageId")
    void Should_RestrictToPage_When_ScopeHasPageId() {
        stubEvents(event("p1", null, detection("EMAIL", "fp-1")),
                event("p2", null, detection("EMAIL", "fp-2")));

        ResolvedSelection resolved = resolver.resolve(RemediationSelection.builder()
                .spaceKey(SPACE_KEY)
                .pageId("p2")
                .piiTypes(List.of("EMAIL"))
                .build());

        assertSoftly(softly -> {
            softly.assertThat(resolved.pageFindings()).hasSize(1);
            softly.assertThat(resolved.pageFindings().getFirst().reference().pageId()).isEqualTo("p2");
        });
    }

    private void stubEvents(ConfluenceContentScanResult... events) {
        when(scanResultQuery.listItemEventsEncryptedByScanIdAndSpaceKey(SCAN_ID, SPACE_KEY))
                .thenReturn(List.of(events));
    }

    private static RemediationSelection selectionOfTypes(String... piiTypes) {
        return RemediationSelection.builder()
                .spaceKey(SPACE_KEY)
                .piiTypes(List.of(piiTypes))
                .build();
    }

    private static ConfluenceContentScanResult event(String pageId, String attachmentName,
                                                     DetectedPersonallyIdentifiableInformation... detections) {
        return ConfluenceContentScanResult.builder()
                .scanId(SCAN_ID)
                .spaceKey(SPACE_KEY)
                .eventType(attachmentName == null ? "item" : "attachmentItem")
                .pageId(pageId)
                .pageTitle("Page " + pageId)
                .attachmentName(attachmentName)
                .detectedPIIs(List.of(detections))
                .build();
    }

    private static DetectedPersonallyIdentifiableInformation detection(String piiType, String fingerprint) {
        return DetectedPersonallyIdentifiableInformation.builder()
                .startPosition(0)
                .endPosition(10)
                .piiType(piiType)
                .piiTypeLabel(piiType)
                .confidence(0.9)
                .sensitiveValue("ENC:v1:opaque")
                .maskedContext("masked-" + piiType)
                .source(DetectorSource.PRESIDIO)
                .valueFingerprint(fingerprint)
                .build();
    }
}
