package pro.softcom.aisentinel.application.pii.remediation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.remediation.service.ScanEventFindingResolver.Resolution;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScanEventFindingResolver")
class ScanEventFindingResolverTest {

    @Mock
    private SeverityCalculationService severityCalculationService;

    private ScanEventFindingResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ScanEventFindingResolver(severityCalculationService);
        lenient().when(severityCalculationService.calculateSeverity(anyString()))
                .thenReturn(PersonallyIdentifiableInformationSeverity.MEDIUM);
    }

    @Test
    @DisplayName("Should_ResolveReferenceAndMetadata_When_DetectionHasFingerprint")
    void Should_ResolveReferenceAndMetadata_When_DetectionHasFingerprint() {
        ConfluenceContentScanResult event = event("p1", "Alpha Page", null,
                List.of(detection("EMAIL", "Email Address", "fp-1", 0.9, DetectorSource.PRESIDIO)));

        Resolution resolution = resolver.resolve(List.of(event));

        assertThat(resolution.findings()).hasSize(1);
        EligibleFinding finding = resolution.findings().getFirst();
        assertSoftly(softly -> {
            softly.assertThat(finding.reference().spaceKey()).isEqualTo("SPACE");
            softly.assertThat(finding.reference().pageId()).isEqualTo("p1");
            softly.assertThat(finding.reference().attachmentName()).isNull();
            softly.assertThat(finding.reference().detector()).isEqualTo("PRESIDIO");
            softly.assertThat(finding.reference().piiType()).isEqualTo("EMAIL");
            softly.assertThat(finding.reference().severity())
                    .isEqualTo(PersonallyIdentifiableInformationSeverity.MEDIUM);
            softly.assertThat(finding.reference().valueFingerprint()).isEqualTo("fp-1");
            softly.assertThat(finding.findingId()).isEqualTo(finding.reference().findingId());
            softly.assertThat(finding.confidence()).isEqualTo(0.9);
            softly.assertThat(finding.piiTypeLabel()).isEqualTo("Email Address");
            softly.assertThat(finding.maskedContext()).isEqualTo("masked-EMAIL");
            softly.assertThat(finding.sensitiveContext()).isEqualTo("ENC:v1:context");
            softly.assertThat(finding.pageTitle()).isEqualTo("Alpha Page");
        });
    }

    @Test
    @DisplayName("Should_CollapseDuplicates_When_SameValueDetectedTwiceOnSameItem")
    void Should_CollapseDuplicates_When_SameValueDetectedTwiceOnSameItem() {
        ConfluenceContentScanResult event = event("p1", "Alpha Page", null, List.of(
                detection("EMAIL", "Email Address", "fp-1", 0.9, DetectorSource.PRESIDIO),
                detection("EMAIL", "Email Address", "fp-1", 0.5, DetectorSource.PRESIDIO)));

        Resolution resolution = resolver.resolve(List.of(event));

        assertSoftly(softly -> {
            softly.assertThat(resolution.findings()).hasSize(1);
            softly.assertThat(resolution.findings().getFirst().confidence()).isEqualTo(0.9);
        });
    }

    @Test
    @DisplayName("Should_CountLegacyDetections_When_FingerprintMissingOrBlank")
    void Should_CountLegacyDetections_When_FingerprintMissingOrBlank() {
        ConfluenceContentScanResult event = event("p1", "Alpha Page", null, List.of(
                detection("EMAIL", "Email Address", null, 0.9, DetectorSource.PRESIDIO),
                detection("PHONE", "Phone", " ", 0.8, DetectorSource.REGEX),
                detection("IBAN", "IBAN", "fp-1", 0.8, DetectorSource.REGEX)));

        Resolution resolution = resolver.resolve(List.of(event));

        assertSoftly(softly -> {
            softly.assertThat(resolution.nonEligibleLegacyCount()).isEqualTo(2);
            softly.assertThat(resolution.findings()).hasSize(1);
        });
    }

    @Test
    @DisplayName("Should_FallBackToUnknownSource_When_DetectorMissing")
    void Should_FallBackToUnknownSource_When_DetectorMissing() {
        ConfluenceContentScanResult event = event("p1", "Alpha Page", null,
                List.of(detection("EMAIL", "Email Address", "fp-1", 0.9, null)));

        Resolution resolution = resolver.resolve(List.of(event));

        assertThat(resolution.findings().getFirst().reference().detector()).isEqualTo("UNKNOWN_SOURCE");
    }

    @Test
    @DisplayName("Should_ReturnStableFindingIdEqualToResolvedFinding_When_DetectionHasFingerprint")
    void Should_ReturnStableFindingIdEqualToResolvedFinding_When_DetectionHasFingerprint() {
        DetectedPersonallyIdentifiableInformation detection =
                detection("EMAIL", "Email Address", "fp-1", 0.9, DetectorSource.PRESIDIO);
        ConfluenceContentScanResult event = event("p1", "Alpha Page", null, List.of(detection));

        String stableId = resolver.stableFindingId(event, detection);

        assertThat(stableId).isEqualTo(resolver.resolve(List.of(event)).findings().getFirst().findingId());
    }

    @Test
    @DisplayName("Should_ReturnNullStableFindingId_When_DetectionIsLegacy")
    void Should_ReturnNullStableFindingId_When_DetectionIsLegacy() {
        ConfluenceContentScanResult event = event("p1", "Alpha Page", null, List.of());

        assertSoftly(softly -> {
            softly.assertThat(resolver.stableFindingId(event,
                    detection("EMAIL", "Email Address", null, 0.9, DetectorSource.PRESIDIO))).isNull();
            softly.assertThat(resolver.stableFindingId(event,
                    detection("PHONE", "Phone", " ", 0.8, DetectorSource.REGEX))).isNull();
        });
    }

    @Test
    @DisplayName("Should_ReturnNullStableFindingId_When_IdentityInvariantViolated")
    void Should_ReturnNullStableFindingId_When_IdentityInvariantViolated() {
        // A detection carrying a real value (fingerprint set) but a blank piiType violates the
        // FindingReference invariant; it has no stable identity and must not abort the read path.
        ConfluenceContentScanResult event = event("p1", "Alpha Page", null, List.of());

        assertThat(resolver.stableFindingId(event,
                detection(" ", "Blank type", "fp-1", 0.9, DetectorSource.PRESIDIO))).isNull();
    }

    @Test
    @DisplayName("Should_ReturnEmptyResolution_When_EventHasNoDetections")
    void Should_ReturnEmptyResolution_When_EventHasNoDetections() {
        ConfluenceContentScanResult event = event("p1", "Alpha Page", null, null);

        Resolution resolution = resolver.resolve(List.of(event));

        assertSoftly(softly -> {
            softly.assertThat(resolution.findings()).isEmpty();
            softly.assertThat(resolution.nonEligibleLegacyCount()).isZero();
        });
    }

    private static ConfluenceContentScanResult event(String pageId, String pageTitle, String attachmentName,
                                                     List<DetectedPersonallyIdentifiableInformation> detections) {
        return ConfluenceContentScanResult.builder()
                .scanId("scan-1")
                .spaceKey("SPACE")
                .eventType(attachmentName == null ? "item" : "attachmentItem")
                .pageId(pageId)
                .pageTitle(pageTitle)
                .attachmentName(attachmentName)
                .detectedPIIList(detections)
                .build();
    }

    private static DetectedPersonallyIdentifiableInformation detection(String piiType, String label,
                                                                       String fingerprint, double confidence,
                                                                       DetectorSource source) {
        return DetectedPersonallyIdentifiableInformation.builder()
                .startPosition(0)
                .endPosition(10)
                .piiType(piiType)
                .piiTypeLabel(label)
                .confidence(confidence)
                .sensitiveValue("ENC:v1:opaque")
                .sensitiveContext("ENC:v1:context")
                .maskedContext("masked-" + piiType)
                .source(source)
                .valueFingerprint(fingerprint)
                .build();
    }
}
