package pro.softcom.aisentinel.application.pii.reporting.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;
import pro.softcom.aisentinel.application.pii.reporting.service.DashboardFalsePositiveFilter;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.LastScanMeta;
import pro.softcom.aisentinel.domain.pii.reporting.ScanCheckpoint;
import pro.softcom.aisentinel.domain.pii.scan.ConfluenceSpaceScanState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ScanReportingUseCase covering branches not covered by integration tests.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScanReportingUseCase - unit tests")
class ScanReportingUseCaseUnitTest {

    @Mock
    private ScanResultQuery scanResultQuery;

    @Mock
    private ScanCheckpointRepository checkpointRepo;

    @Mock
    private DashboardFalsePositiveFilter falsePositiveFilter;

    private ScanReportingUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ScanReportingUseCase(scanResultQuery, checkpointRepo, falsePositiveFilter);
    }

    @Nested
    @DisplayName("getLatestSpaceScanStateList")
    class GetLatestSpaceScanStateList {

        @Test
        @DisplayName("Should_ReturnEmptyList_When_ScanIdIsNull")
        void Should_ReturnEmptyList_When_ScanIdIsNull() {
            List<ConfluenceSpaceScanState> result = useCase.getLatestSpaceScanStateList(null);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnEmptyList_When_ScanIdIsBlank")
        void Should_ReturnEmptyList_When_ScanIdIsBlank() {
            List<ConfluenceSpaceScanState> result = useCase.getLatestSpaceScanStateList("  ");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnPausedStatus_When_NoCheckpointButEventsExist")
        void Should_ReturnPausedStatus_When_NoCheckpointButEventsExist() {
            // Arrange: no checkpoint for SPACE-X, but counters show 1 page done
            String scanId = "scan-unit-1";
            when(checkpointRepo.findByScan(scanId)).thenReturn(List.of());
            when(scanResultQuery.getSpaceCounters(scanId)).thenReturn(
                    List.of(new ScanResultQuery.SpaceCounter("SPACE-X", 1L, 0L, Instant.now()))
            );

            // Act
            List<ConfluenceSpaceScanState> states = useCase.getLatestSpaceScanStateList(scanId);

            // Assert: status = PAUSED (progress > 0 but no checkpoint)
            assertSoftly(softly -> {
                softly.assertThat(states).hasSize(1);
                softly.assertThat(states.getFirst().spaceKey()).isEqualTo("SPACE-X");
                softly.assertThat(states.getFirst().status()).isEqualTo("PAUSED");
            });
        }

        @Test
        @DisplayName("Should_ReturnNotStartedStatus_When_NoCheckpointAndNoEvents")
        void Should_ReturnNotStartedStatus_When_NoCheckpointAndNoEvents() {
            // Arrange: no checkpoint, counter shows 0 pages
            String scanId = "scan-unit-2";
            when(checkpointRepo.findByScan(scanId)).thenReturn(List.of());
            when(scanResultQuery.getSpaceCounters(scanId)).thenReturn(
                    List.of(new ScanResultQuery.SpaceCounter("SPACE-Y", 0L, 0L, Instant.now()))
            );

            // Act
            List<ConfluenceSpaceScanState> states = useCase.getLatestSpaceScanStateList(scanId);

            // Assert
            assertThat(states.getFirst().status()).isEqualTo("NOT_STARTED");
        }

        @Test
        @DisplayName("Should_ReturnPausedStatus_When_CheckpointStatusIsPaused")
        void Should_ReturnPausedStatus_When_CheckpointStatusIsPaused() {
            // Arrange
            String scanId = "scan-unit-paused";
            ScanCheckpoint cp = ScanCheckpoint.builder()
                    .scanId(scanId).spaceKey("SPACE-P")
                    .scanStatus(ScanStatus.PAUSED).progressPercentage(50.0).build();
            when(checkpointRepo.findByScan(scanId)).thenReturn(List.of(cp));
            when(scanResultQuery.getSpaceCounters(scanId)).thenReturn(
                    List.of(new ScanResultQuery.SpaceCounter("SPACE-P", 2L, 0L, Instant.now()))
            );

            // Act
            List<ConfluenceSpaceScanState> states = useCase.getLatestSpaceScanStateList(scanId);

            // Assert
            assertThat(states.getFirst().status()).isEqualTo("PAUSED");
        }

        @Test
        @DisplayName("Should_ReturnNotStartedStatus_When_CheckpointStatusIsNotStarted")
        void Should_ReturnNotStartedStatus_When_CheckpointStatusIsNotStarted() {
            // Arrange
            String scanId = "scan-unit-not-started";
            ScanCheckpoint cp = ScanCheckpoint.builder()
                    .scanId(scanId).spaceKey("SPACE-NS")
                    .scanStatus(ScanStatus.NOT_STARTED).progressPercentage(0.0).build();
            when(checkpointRepo.findByScan(scanId)).thenReturn(List.of(cp));
            when(scanResultQuery.getSpaceCounters(scanId)).thenReturn(
                    List.of(new ScanResultQuery.SpaceCounter("SPACE-NS", 0L, 0L, Instant.now()))
            );

            // Act
            List<ConfluenceSpaceScanState> states = useCase.getLatestSpaceScanStateList(scanId);

            // Assert
            assertThat(states.getFirst().status()).isEqualTo("NOT_STARTED");
        }
    }

    @Nested
    @DisplayName("getScanReportingSummary")
    class GetScanReportingSummary {

        @Test
        @DisplayName("Should_ReturnEmpty_When_ScanIdIsNull")
        void Should_ReturnEmpty_When_ScanIdIsNull() {
            assertThat(useCase.getScanReportingSummary(null)).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnEmpty_When_ScanIdIsBlank")
        void Should_ReturnEmpty_When_ScanIdIsBlank() {
            assertThat(useCase.getScanReportingSummary("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("getLatestScan - error handling")
    class GetLatestScanErrorHandling {

        @Test
        @DisplayName("Should_ReturnEmpty_When_QueryThrowsException")
        void Should_ReturnEmpty_When_QueryThrowsException() {
            // Arrange
            when(scanResultQuery.findLatestScan()).thenThrow(new RuntimeException("DB error"));

            // Act
            Optional<LastScanMeta> result = useCase.getLatestScan();

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getGlobalScanSummary")
    class GetGlobalScanSummary {

        @Test
        @DisplayName("Should_ReturnEmpty_When_NoLatestCheckpoints")
        void Should_ReturnEmpty_When_NoLatestCheckpoints() {
            // Arrange
            when(checkpointRepo.findAllLatestCheckpoints()).thenReturn(List.of());

            // Act
            var result = useCase.getGlobalScanSummary();

            // Assert
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getLatestSpaceScanResultList")
    class GetLatestSpaceScanResultList {

        @Test
        @DisplayName("Should_ReturnEmptyList_When_NoLatestScan")
        void Should_ReturnEmptyList_When_NoLatestScan() {
            // Arrange
            when(scanResultQuery.findLatestScan()).thenReturn(Optional.empty());

            // Act
            List<ConfluenceContentScanResult> result = useCase.getLatestSpaceScanResultList();

            // Assert
            assertThat(result).isEmpty();
        }
    }
}
