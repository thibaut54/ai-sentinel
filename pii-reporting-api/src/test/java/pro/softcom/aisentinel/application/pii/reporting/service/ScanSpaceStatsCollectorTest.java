package pro.softcom.aisentinel.application.pii.reporting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanSpaceStatsRepository;
import pro.softcom.aisentinel.application.pii.reporting.usecase.DetectionReportingEventType;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorRunStat;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ScanSpaceStatsCollectorTest {

    private static final String SCAN_ID = "scan-1";
    private static final String SPACE_KEY = "KEY";

    @Mock
    private ScanSpaceStatsRepository repository;

    private ScanSpaceStatsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ScanSpaceStatsCollector(repository);
    }

    private static ConfluenceContentScanResult.ConfluenceContentScanResultBuilder event(DetectionReportingEventType type) {
        return ConfluenceContentScanResult.builder()
            .scanId(SCAN_ID)
            .spaceKey(SPACE_KEY)
            .eventType(type.getLabel());
    }

    @Test
    @DisplayName("Should_MarkStarted_When_StartEventReceived")
    void Should_MarkStarted_When_StartEventReceived() {
        collector.recordEvent(event(DetectionReportingEventType.START).build());

        verify(repository).markStarted(eq(SCAN_ID), eq(SPACE_KEY), any());
    }

    @Test
    @DisplayName("Should_MarkFinished_When_CompleteEventReceived")
    void Should_MarkFinished_When_CompleteEventReceived() {
        collector.recordEvent(event(DetectionReportingEventType.COMPLETE).build());

        verify(repository).markFinished(eq(SCAN_ID), eq(SPACE_KEY), any());
    }

    @Test
    @DisplayName("Should_IncrementPageScannedWithContentLength_When_PageItemEventReceived")
    void Should_IncrementPageScannedWithContentLength_When_PageItemEventReceived() {
        ConfluenceContentScanResult ev = event(DetectionReportingEventType.ITEM)
            .sourceContent("hello world")
            .build();

        collector.recordEvent(ev);

        verify(repository).incrementPageScanned(SCAN_ID, SPACE_KEY, 11L);
    }

    @Test
    @DisplayName("Should_IncrementPageScannedWithZeroChars_When_SourceContentNull")
    void Should_IncrementPageScannedWithZeroChars_When_SourceContentNull() {
        collector.recordEvent(event(DetectionReportingEventType.ITEM).build());

        verify(repository).incrementPageScanned(SCAN_ID, SPACE_KEY, 0L);
    }

    @Test
    @DisplayName("Should_IncrementAttachmentScannedWithContentLength_When_AttachmentItemEventReceived")
    void Should_IncrementAttachmentScannedWithContentLength_When_AttachmentItemEventReceived() {
        ConfluenceContentScanResult ev = event(DetectionReportingEventType.ATTACHMENT_ITEM)
            .attachmentName("file.pdf")
            .sourceContent("abcde")
            .build();

        collector.recordEvent(ev);

        verify(repository).incrementAttachmentScanned(SCAN_ID, SPACE_KEY, 5L);
    }

    @Test
    @DisplayName("Should_IncrementPageFailed_When_ErrorEventWithoutAttachment")
    void Should_IncrementPageFailed_When_ErrorEventWithoutAttachment() {
        collector.recordEvent(event(DetectionReportingEventType.ERROR).pageId("p1").build());

        verify(repository).incrementPageFailed(SCAN_ID, SPACE_KEY);
        verify(repository, never()).incrementAttachmentFailed(any(), any());
    }

    @Test
    @DisplayName("Should_IncrementAttachmentFailed_When_ErrorEventHasAttachmentName")
    void Should_IncrementAttachmentFailed_When_ErrorEventHasAttachmentName() {
        collector.recordEvent(event(DetectionReportingEventType.ERROR).attachmentName("file.pdf").build());

        verify(repository).incrementAttachmentFailed(SCAN_ID, SPACE_KEY);
        verify(repository, never()).incrementPageFailed(any(), any());
    }

    @Test
    @DisplayName("Should_AccumulateOnePerDetector_When_ItemEventCarriesDetectorStats")
    void Should_AccumulateOnePerDetector_When_ItemEventCarriesDetectorStats() {
        ConfluenceContentScanResult ev = event(DetectionReportingEventType.ITEM)
            .sourceContent("1234567890")
            .detectorRunStats(List.of(
                new DetectorRunStat(DetectorSource.MINISTRAL, 520L, 12, 0),
                new DetectorRunStat(DetectorSource.POSTFILTER, 1_400L, 12, 4)))
            .build();

        collector.recordEvent(ev);

        verify(repository).accumulateDetectorStat(SCAN_ID, SPACE_KEY, "MINISTRAL", 520L, 10L, 12, 0);
        verify(repository).accumulateDetectorStat(SCAN_ID, SPACE_KEY, "POSTFILTER", 1_400L, 10L, 12, 4);
    }

    @Test
    @DisplayName("Should_NotAccumulateDetectors_When_StatsAbsent")
    void Should_NotAccumulateDetectors_When_StatsAbsent() {
        collector.recordEvent(event(DetectionReportingEventType.ITEM).sourceContent("x").build());

        verify(repository, never()).accumulateDetectorStat(any(), any(), any(), anyLong(), anyLong(),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("Should_IgnoreEvent_When_ScanIdNull")
    void Should_IgnoreEvent_When_ScanIdNull() {
        collector.recordEvent(ConfluenceContentScanResult.builder().spaceKey(SPACE_KEY)
            .eventType(DetectionReportingEventType.START.getLabel()).build());

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Should_IgnoreNullEvent_When_RecordingNothing")
    void Should_IgnoreNullEvent_When_RecordingNothing() {
        collector.recordEvent(null);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Should_IgnoreUnknownEventType_When_EventTypeNotRecognized")
    void Should_IgnoreUnknownEventType_When_EventTypeNotRecognized() {
        collector.recordEvent(ConfluenceContentScanResult.builder()
            .scanId(SCAN_ID).spaceKey(SPACE_KEY).eventType("pageStart").build());

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Should_SwallowRepositoryFailure_When_PersistenceThrows")
    void Should_SwallowRepositoryFailure_When_PersistenceThrows() {
        doThrow(new RuntimeException("db down"))
            .when(repository).markStarted(any(), any(), any());
        ConfluenceContentScanResult startEvent = event(DetectionReportingEventType.START).build();

        // Must not propagate: stats collection can never fail the scan.
        assertThatCode(() -> collector.recordEvent(startEvent))
            .doesNotThrowAnyException();
    }
}
