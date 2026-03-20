package pro.softcom.aisentinel.infrastructure.pii.export.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanEventJpaAdapterTest {

    @Mock
    private ScanResultQuery scanResultQuery;

    private ScanEventJpaAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ScanEventJpaAdapter(scanResultQuery);
    }

    @Test
    void Should_ReturnStreamOfEvents_When_EventsExist() {
        // Arrange
        ContentScanResult event1 = ContentScanResult.builder().scanId("scan-1").eventType("ITEM").build();
        ContentScanResult event2 = ContentScanResult.builder().scanId("scan-1").eventType("ITEM").build();
        when(scanResultQuery.listItemEventsEncryptedBySourceKey("scan-1", "SPACE1"))
                .thenReturn(List.of(event1, event2));

        // Act
        Stream<ContentScanResult> result = adapter.streamByScanIdAndSourceKey("scan-1", "SPACE1");

        // Assert
        assertThat(result).hasSize(2);
        verify(scanResultQuery).listItemEventsEncryptedBySourceKey("scan-1", "SPACE1");
    }

    @Test
    void Should_ReturnEmptyStream_When_NoEventsExist() {
        // Arrange
        when(scanResultQuery.listItemEventsEncryptedBySourceKey("scan-1", "SPACE1"))
                .thenReturn(List.of());

        // Act
        Stream<ContentScanResult> result = adapter.streamByScanIdAndSourceKey("scan-1", "SPACE1");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_DelegateCorrectParameters_When_Called() {
        // Arrange
        when(scanResultQuery.listItemEventsEncryptedBySourceKey("scan-abc", "KEY-XYZ"))
                .thenReturn(List.of());

        // Act
        adapter.streamByScanIdAndSourceKey("scan-abc", "KEY-XYZ");

        // Assert
        verify(scanResultQuery).listItemEventsEncryptedBySourceKey("scan-abc", "KEY-XYZ");
    }
}
