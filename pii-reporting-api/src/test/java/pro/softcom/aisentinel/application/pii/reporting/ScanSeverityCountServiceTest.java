package pro.softcom.aisentinel.application.pii.reporting;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanSeverityCountRepository;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ScanSeverityCount;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanSeverityCountServiceTest {

    @Mock
    private ScanSeverityCountRepository repository;

    @InjectMocks
    private ScanSeverityCountService service;

    @Nested
    class IncrementCounts {

        @Test
        void Should_DelegateToRepository_When_ValidInput() {
            String scanId = "scan-123";
            String sourceKey = "SPACE-A";
            SeverityCounts delta = new SeverityCounts(5, 3, 2);

            service.incrementCounts(scanId, SourceType.CONFLUENCE, sourceKey, delta);

            verify(repository).incrementCounts(scanId, SourceType.CONFLUENCE, sourceKey, delta);
        }

        @Test
        void Should_ThrowException_When_ScanIdIsNull() {
            SeverityCounts delta = new SeverityCounts(1, 1, 1);

            assertThatThrownBy(() -> service.incrementCounts(null, SourceType.CONFLUENCE, "SPACE-A", delta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scanId must not be null or blank");
        }

        @Test
        void Should_ThrowException_When_ScanIdIsBlank() {
            SeverityCounts delta = new SeverityCounts(1, 1, 1);

            assertThatThrownBy(() -> service.incrementCounts("  ", SourceType.CONFLUENCE, "SPACE-A", delta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scanId must not be null or blank");
        }

        @Test
        void Should_ThrowException_When_SourceTypeIsNull() {
            SeverityCounts delta = new SeverityCounts(1, 1, 1);

            assertThatThrownBy(() -> service.incrementCounts("scan-123", null, "SPACE-A", delta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceType must not be null");
        }

        @Test
        void Should_ThrowException_When_SourceKeyIsNull() {
            SeverityCounts delta = new SeverityCounts(1, 1, 1);

            assertThatThrownBy(() -> service.incrementCounts("scan-123", SourceType.CONFLUENCE, null, delta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceKey must not be null or blank");
        }

        @Test
        void Should_ThrowException_When_SourceKeyIsBlank() {
            SeverityCounts delta = new SeverityCounts(1, 1, 1);

            assertThatThrownBy(() -> service.incrementCounts("scan-123", SourceType.CONFLUENCE, "", delta))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceKey must not be null or blank");
        }

        @Test
        void Should_ThrowException_When_DeltaIsNull() {
            assertThatThrownBy(() -> service.incrementCounts("scan-123", SourceType.CONFLUENCE, "SPACE-A", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("delta must not be null");
        }
    }

    @Nested
    class GetCounts {

        @Test
        void Should_ReturnCounts_When_RecordExists() {
            String scanId = "scan-123";
            String sourceKey = "SPACE-A";
            SeverityCounts expected = new SeverityCounts(10, 5, 3);
            when(repository.findByScanIdAndSource(scanId, SourceType.CONFLUENCE, sourceKey))
                .thenReturn(Optional.of(expected));

            Optional<SeverityCounts> result = service.getCounts(scanId, SourceType.CONFLUENCE, sourceKey);

            assertThat(result).isPresent().contains(expected);
            verify(repository).findByScanIdAndSource(scanId, SourceType.CONFLUENCE, sourceKey);
        }

        @Test
        void Should_ReturnEmpty_When_RecordDoesNotExist() {
            String scanId = "scan-123";
            String sourceKey = "SPACE-A";
            when(repository.findByScanIdAndSource(scanId, SourceType.CONFLUENCE, sourceKey))
                .thenReturn(Optional.empty());

            Optional<SeverityCounts> result = service.getCounts(scanId, SourceType.CONFLUENCE, sourceKey);

            assertThat(result).isEmpty();
            verify(repository).findByScanIdAndSource(scanId, SourceType.CONFLUENCE, sourceKey);
        }

        @Test
        void Should_ThrowException_When_ScanIdIsNull() {
            assertThatThrownBy(() -> service.getCounts(null, SourceType.CONFLUENCE, "SPACE-A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scanId must not be null or blank");
        }

        @Test
        void Should_ThrowException_When_SourceKeyIsNull() {
            assertThatThrownBy(() -> service.getCounts("scan-123", SourceType.CONFLUENCE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("sourceKey must not be null or blank");
        }
    }

    @Nested
    class GetCountsByScan {

        @Test
        void Should_ReturnListOfCounts_When_ScanHasData() {
            String scanId = "scan-123";
            List<ScanSeverityCount> expected = List.of(
                new ScanSeverityCount(scanId, SourceType.CONFLUENCE, "SPACE-A", new SeverityCounts(5, 3, 2)),
                new ScanSeverityCount(scanId, SourceType.CONFLUENCE, "SPACE-B", new SeverityCounts(8, 4, 1))
            );
            when(repository.findByScanId(scanId)).thenReturn(expected);

            List<ScanSeverityCount> result = service.getCountsByScan(scanId);

            assertThat(result).hasSize(2).isEqualTo(expected);
            verify(repository).findByScanId(scanId);
        }

        @Test
        void Should_ReturnEmptyList_When_ScanHasNoData() {
            String scanId = "scan-123";
            when(repository.findByScanId(scanId)).thenReturn(List.of());

            List<ScanSeverityCount> result = service.getCountsByScan(scanId);

            assertThat(result).isEmpty();
            verify(repository).findByScanId(scanId);
        }

        @Test
        void Should_ThrowException_When_ScanIdIsNull() {
            assertThatThrownBy(() -> service.getCountsByScan(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scanId must not be null or blank");
        }

        @Test
        void Should_ThrowException_When_ScanIdIsBlank() {
            assertThatThrownBy(() -> service.getCountsByScan(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scanId must not be null or blank");
        }
    }

    @Nested
    class DeleteCounts {

        @Test
        void Should_DelegateToRepository_When_ValidScanId() {
            String scanId = "scan-123";

            service.deleteCounts(scanId);

            verify(repository).deleteByScanId(scanId);
        }

        @Test
        void Should_ThrowException_When_ScanIdIsNull() {
            assertThatThrownBy(() -> service.deleteCounts(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scanId must not be null or blank");
        }

        @Test
        void Should_ThrowException_When_ScanIdIsBlank() {
            assertThatThrownBy(() -> service.deleteCounts("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("scanId must not be null or blank");
        }
    }
}
