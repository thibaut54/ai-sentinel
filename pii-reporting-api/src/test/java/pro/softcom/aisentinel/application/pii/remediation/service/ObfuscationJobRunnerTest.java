package pro.softcom.aisentinel.application.pii.remediation.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.remediation.port.out.FindingRemediationStore;
import pro.softcom.aisentinel.application.pii.remediation.port.out.ObfuscationJobStore;
import pro.softcom.aisentinel.application.pii.remediation.port.out.SourcePageRedactionPort;
import pro.softcom.aisentinel.application.pii.remediation.port.out.SourcePageRedactionPort.PageRedactionResult;
import pro.softcom.aisentinel.application.pii.remediation.port.out.SourcePageRedactionPort.PageRedactionStatus;
import pro.softcom.aisentinel.application.pii.remediation.port.out.SourcePageRedactionPort.ValueRedactionStatus;
import pro.softcom.aisentinel.application.pii.remediation.port.out.SourcePageRedactionPort.ValueReplacement;
import pro.softcom.aisentinel.application.pii.remediation.service.SelectionResolver.ResolvedSelection;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRedactionOutcome;
import pro.softcom.aisentinel.domain.pii.remediation.FindingReference;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediation;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJob;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJobStatus;
import pro.softcom.aisentinel.domain.pii.remediation.RedactionOutcome;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;
import pro.softcom.aisentinel.domain.pii.reporting.AccessPurpose;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ObfuscationJobRunner")
class ObfuscationJobRunnerTest {

    private static final String SCAN_ID = "scan-1";
    private static final String SPACE_KEY = "SPACE";
    private static final String JOB_ID = "job-42";
    private static final Instant NOW = Instant.parse("2026-07-06T10:00:00Z");

    @Mock
    private ScanResultQuery scanResultQuery;

    @Mock
    private FindingRemediationStore findingRemediationStore;

    @Mock
    private ObfuscationJobStore obfuscationJobStore;

    @Mock
    private SourcePageRedactionPort sourcePageRedactionPort;

    private ObfuscationJobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new ObfuscationJobRunner(scanResultQuery, findingRemediationStore,
                obfuscationJobStore, sourcePageRedactionPort, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(findingRemediationStore.findByIds(any())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("run")
    class Run {

        @Test
        @DisplayName("Should_RedactAndPersistStatuses_When_AllValuesFoundOnPage")
        void Should_RedactAndPersistStatuses_When_AllValuesFoundOnPage() {
            EligibleFinding finding = finding("EMAIL", "p1", "fp-1");
            stubDecryptedValue("p1", "EMAIL", "fp-1", "john.doe@example.com");
            when(sourcePageRedactionPort.redactPage(eq("p1"), anyList()))
                    .thenReturn(new PageRedactionResult(PageRedactionStatus.UPDATED,
                            List.of(ValueRedactionStatus.REDACTED)));

            runner.run(job(finding), resolved(finding));

            verify(scanResultQuery).listItemEventsDecrypted(SCAN_ID, "p1", AccessPurpose.REDACTION);
            ArgumentCaptor<List<ValueReplacement>> replacementsCaptor = ArgumentCaptor.captor();
            verify(sourcePageRedactionPort).redactPage(eq("p1"), replacementsCaptor.capture());
            assertThat(replacementsCaptor.getValue())
                    .containsExactly(new ValueReplacement("john.doe@example.com", "[EMAIL]"));

            ArgumentCaptor<Collection<FindingRemediation>> rowsCaptor = ArgumentCaptor.captor();
            verify(findingRemediationStore).upsertAll(rowsCaptor.capture());
            FindingRemediation row = rowsCaptor.getValue().iterator().next();
            ObfuscationJob finalJob = lastJobUpdate();
            assertSoftly(softly -> {
                softly.assertThat(row.findingId()).isEqualTo(finding.findingId());
                softly.assertThat(row.status()).isEqualTo(FindingRemediationStatus.REDACTED);
                softly.assertThat(row.redactionJobId()).isEqualTo(JOB_ID);
                softly.assertThat(row.scanId()).isEqualTo(SCAN_ID);
                softly.assertThat(finalJob.status()).isEqualTo(ObfuscationJobStatus.COMPLETED);
                softly.assertThat(finalJob.processed()).isEqualTo(1);
                softly.assertThat(finalJob.outcomes().get(finding.findingId()).outcome())
                        .isEqualTo(RedactionOutcome.REDACTED);
            });
        }

        @Test
        @DisplayName("Should_LeaveFindingPendingWithSkippedOutcome_When_ValueNotFoundInPage")
        void Should_LeaveFindingPendingWithSkippedOutcome_When_ValueNotFoundInPage() {
            EligibleFinding finding = finding("EMAIL", "p1", "fp-1");
            stubDecryptedValue("p1", "EMAIL", "fp-1", "john.doe@example.com");
            when(sourcePageRedactionPort.redactPage(eq("p1"), anyList()))
                    .thenReturn(new PageRedactionResult(PageRedactionStatus.NO_MATCHES,
                            List.of(ValueRedactionStatus.VALUE_NOT_FOUND)));

            runner.run(job(finding), resolved(finding));

            verify(findingRemediationStore, never()).upsertAll(any());
            ObfuscationJob finalJob = lastJobUpdate();
            FindingRedactionOutcome outcome = finalJob.outcomes().get(finding.findingId());
            assertSoftly(softly -> {
                softly.assertThat(finalJob.status()).isEqualTo(ObfuscationJobStatus.COMPLETED_WITH_ERRORS);
                softly.assertThat(outcome.outcome()).isEqualTo(RedactionOutcome.SKIPPED_VALUE_NOT_FOUND);
                softly.assertThat(outcome.reason()).isNotBlank();
            });
        }

        @Test
        @DisplayName("Should_SkipPageAsStale_When_PortReportsStalePage")
        void Should_SkipPageAsStale_When_PortReportsStalePage() {
            EligibleFinding finding = finding("EMAIL", "p1", "fp-1");
            stubDecryptedValue("p1", "EMAIL", "fp-1", "john.doe@example.com");
            when(sourcePageRedactionPort.redactPage(eq("p1"), anyList()))
                    .thenReturn(PageRedactionResult.stale());

            runner.run(job(finding), resolved(finding));

            ObfuscationJob finalJob = lastJobUpdate();
            assertSoftly(softly -> {
                softly.assertThat(finalJob.status()).isEqualTo(ObfuscationJobStatus.COMPLETED_WITH_ERRORS);
                softly.assertThat(finalJob.outcomes().get(finding.findingId()).outcome())
                        .isEqualTo(RedactionOutcome.SKIPPED_STALE);
            });
        }

        @Test
        @DisplayName("Should_ContinueWithNextPage_When_OnePageFails")
        void Should_ContinueWithNextPage_When_OnePageFails() {
            EligibleFinding failing = finding("EMAIL", "p1", "fp-1");
            EligibleFinding succeeding = finding("IBAN", "p2", "fp-2");
            stubDecryptedValue("p1", "EMAIL", "fp-1", "john.doe@example.com");
            stubDecryptedValue("p2", "IBAN", "fp-2", "CH9300762011623852957");
            when(sourcePageRedactionPort.redactPage(eq("p1"), anyList()))
                    .thenReturn(PageRedactionResult.failed());
            when(sourcePageRedactionPort.redactPage(eq("p2"), anyList()))
                    .thenReturn(new PageRedactionResult(PageRedactionStatus.UPDATED,
                            List.of(ValueRedactionStatus.REDACTED)));

            runner.run(job(failing, succeeding), resolved(failing, succeeding));

            ObfuscationJob finalJob = lastJobUpdate();
            assertSoftly(softly -> {
                softly.assertThat(finalJob.status()).isEqualTo(ObfuscationJobStatus.COMPLETED_WITH_ERRORS);
                softly.assertThat(finalJob.processed()).isEqualTo(2);
                softly.assertThat(finalJob.outcomes().get(failing.findingId()).outcome())
                        .isEqualTo(RedactionOutcome.FAILED);
                softly.assertThat(finalJob.outcomes().get(succeeding.findingId()).outcome())
                        .isEqualTo(RedactionOutcome.REDACTED);
            });
        }

        @Test
        @DisplayName("Should_FailFindingWithoutPortCall_When_PlaintextUnavailable")
        void Should_FailFindingWithoutPortCall_When_PlaintextUnavailable() {
            EligibleFinding finding = finding("EMAIL", "p1", "fp-1");
            when(scanResultQuery.listItemEventsDecrypted(SCAN_ID, "p1", AccessPurpose.REDACTION))
                    .thenReturn(List.of());

            runner.run(job(finding), resolved(finding));

            verify(sourcePageRedactionPort, never()).redactPage(anyString(), anyList());
            ObfuscationJob finalJob = lastJobUpdate();
            assertSoftly(softly -> {
                softly.assertThat(finalJob.status()).isEqualTo(ObfuscationJobStatus.COMPLETED_WITH_ERRORS);
                softly.assertThat(finalJob.outcomes().get(finding.findingId()).outcome())
                        .isEqualTo(RedactionOutcome.FAILED);
            });
        }

        @Test
        @DisplayName("Should_SkipAttachmentFindingWithoutDecryption_When_OneSlipsIntoTheResolvedSet")
        void Should_SkipAttachmentFindingWithoutDecryption_When_OneSlipsIntoTheResolvedSet() {
            EligibleFinding attachment = attachmentFinding();

            runner.run(job(attachment), resolved(attachment));

            verify(scanResultQuery, never()).listItemEventsDecrypted(anyString(), anyString(), any());
            ObfuscationJob finalJob = lastJobUpdate();
            assertThat(finalJob.outcomes().get(attachment.findingId()).outcome())
                    .isEqualTo(RedactionOutcome.SKIPPED_ATTACHMENT);
        }

        @Test
        @DisplayName("Should_SendSingleReplacement_When_TwoFindingsShareValueAndType")
        void Should_SendSingleReplacement_When_TwoFindingsShareValueAndType() {
            EligibleFinding presidio = finding("EMAIL", "p1", "fp-1");
            EligibleFinding regex = findingWithDetector("EMAIL", "p1", "fp-1", "REGEX");
            stubDecryptedValue("p1", "EMAIL", "fp-1", "john.doe@example.com");
            when(sourcePageRedactionPort.redactPage(eq("p1"), anyList()))
                    .thenReturn(new PageRedactionResult(PageRedactionStatus.UPDATED,
                            List.of(ValueRedactionStatus.REDACTED)));

            runner.run(job(presidio, regex), resolved(presidio, regex));

            ArgumentCaptor<List<ValueReplacement>> captor = ArgumentCaptor.captor();
            verify(sourcePageRedactionPort).redactPage(eq("p1"), captor.capture());
            ObfuscationJob finalJob = lastJobUpdate();
            assertSoftly(softly -> {
                softly.assertThat(captor.getValue()).hasSize(1);
                softly.assertThat(finalJob.outcomes().get(presidio.findingId()).outcome())
                        .isEqualTo(RedactionOutcome.REDACTED);
                softly.assertThat(finalJob.outcomes().get(regex.findingId()).outcome())
                        .isEqualTo(RedactionOutcome.REDACTED);
                softly.assertThat(finalJob.status()).isEqualTo(ObfuscationJobStatus.COMPLETED);
            });
        }

        @Test
        @DisplayName("Should_MarkJobFailed_When_ProgressPersistenceCrashes")
        void Should_MarkJobFailed_When_ProgressPersistenceCrashes() {
            EligibleFinding finding = finding("EMAIL", "p1", "fp-1");
            stubDecryptedValue("p1", "EMAIL", "fp-1", "john.doe@example.com");
            when(sourcePageRedactionPort.redactPage(eq("p1"), anyList()))
                    .thenReturn(new PageRedactionResult(PageRedactionStatus.UPDATED,
                            List.of(ValueRedactionStatus.REDACTED)));
            ObfuscationJob job = job(finding);
            doThrow(new IllegalStateException("db down")).doNothing()
                    .when(obfuscationJobStore).update(any());
            when(obfuscationJobStore.findById(JOB_ID)).thenReturn(Optional.of(job));

            runner.run(job, resolved(finding));

            ObfuscationJob finalJob = lastJobUpdate();
            assertThat(finalJob.status()).isEqualTo(ObfuscationJobStatus.FAILED);
        }
    }

    private ObfuscationJob lastJobUpdate() {
        ArgumentCaptor<ObfuscationJob> captor = ArgumentCaptor.forClass(ObfuscationJob.class);
        verify(obfuscationJobStore, atLeastOnce()).update(captor.capture());
        return captor.getValue();
    }

    private void stubDecryptedValue(String pageId, String piiType, String fingerprint, String plaintext) {
        DetectedPersonallyIdentifiableInformation detection = DetectedPersonallyIdentifiableInformation.builder()
                .startPosition(0)
                .endPosition(10)
                .piiType(piiType)
                .piiTypeLabel(piiType)
                .confidence(0.9)
                .sensitiveValue(plaintext)
                .maskedContext("masked")
                .source(DetectorSource.PRESIDIO)
                .valueFingerprint(fingerprint)
                .build();
        when(scanResultQuery.listItemEventsDecrypted(SCAN_ID, pageId, AccessPurpose.REDACTION))
                .thenReturn(List.of(ConfluenceContentScanResult.builder()
                        .scanId(SCAN_ID)
                        .spaceKey(SPACE_KEY)
                        .eventType("item")
                        .pageId(pageId)
                        .pageTitle("Page " + pageId)
                        .detectedPIIs(List.of(detection))
                        .build()));
    }

    private static ResolvedSelection resolved(EligibleFinding... findings) {
        return new ResolvedSelection(SCAN_ID, List.of(findings), List.of(), 0);
    }

    private static ObfuscationJob job(EligibleFinding... findings) {
        return ObfuscationJob.builder()
                .id(JOB_ID)
                .spaceKey(SPACE_KEY)
                .status(ObfuscationJobStatus.RUNNING)
                .submittedSelection(RemediationSelection.builder().spaceKey(SPACE_KEY).build())
                .resolvedFindingIds(Stream.of(findings).map(EligibleFinding::findingId).toList())
                .processed(0)
                .total(findings.length)
                .actor("officer")
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private static EligibleFinding finding(String piiType, String pageId, String fingerprint) {
        return findingWithDetector(piiType, pageId, fingerprint, "PRESIDIO");
    }

    private static EligibleFinding findingWithDetector(String piiType, String pageId,
                                                       String fingerprint, String detector) {
        return buildFinding(piiType, pageId, null, fingerprint, detector);
    }

    private static EligibleFinding attachmentFinding() {
        return buildFinding("EMAIL", "p1", "report.xlsx", "fp-1", "PRESIDIO");
    }

    private static EligibleFinding buildFinding(String piiType, String pageId, String attachmentName,
                                                String fingerprint, String detector) {
        FindingReference reference = FindingReference.builder()
                .spaceKey(SPACE_KEY)
                .pageId(pageId)
                .attachmentName(attachmentName)
                .detector(detector)
                .piiType(piiType)
                .severity(PersonallyIdentifiableInformationSeverity.MEDIUM)
                .valueFingerprint(fingerprint)
                .build();
        return EligibleFinding.builder()
                .findingId(reference.findingId())
                .reference(reference)
                .confidence(0.9)
                .piiTypeLabel(piiType)
                .maskedContext("masked")
                .pageTitle("Page " + pageId)
                .build();
    }
}
