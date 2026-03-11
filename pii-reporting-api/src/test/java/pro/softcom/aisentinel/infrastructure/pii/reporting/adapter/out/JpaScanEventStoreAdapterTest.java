package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.reporting.service.PiiContextExtractor;
import pro.softcom.aisentinel.application.pii.security.ScanResultEncryptor;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.DetectionEventRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanEventEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaScanEventStoreAdapterTest {

    @Mock
    private DetectionEventRepository eventRepository;

    @Mock
    private ScanResultEncryptor scanResultEncryptor;

    @Mock
    private PiiContextExtractor piiContextExtractor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JpaScanEventStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JpaScanEventStoreAdapter(eventRepository, scanResultEncryptor, piiContextExtractor, objectMapper);
    }

    @Test
    void Should_PersistEncryptedEvent_When_ValidResultProvided() {
        // Arrange
        ContentScanResult result = ContentScanResult.builder()
                .scanId("scan-1")
                .sourceId("SPACE1")
                .eventType("CONTENT_ITEM")
                .contentId("page-1")
                .contentTitle("Test Page")
                .emittedAt(Instant.now().toString())
                .build();

        when(scanResultEncryptor.encrypt(any())).thenReturn(result);
        when(eventRepository.findMaxEventSeqByScanId("scan-1")).thenReturn(0L);

        // Act
        adapter.append(result, SourceType.CONFLUENCE);

        // Assert
        ArgumentCaptor<ScanEventEntity> captor = ArgumentCaptor.forClass(ScanEventEntity.class);
        verify(eventRepository).save(captor.capture());

        ScanEventEntity entity = captor.getValue();
        assertThat(entity.getScanId()).isEqualTo("scan-1");
        assertThat(entity.getSourceKey()).isEqualTo("SPACE1");
        assertThat(entity.getEventType()).isEqualTo("CONTENT_ITEM");
        assertThat(entity.getSourceType()).isEqualTo("CONFLUENCE");
        assertThat(entity.getEventSeq()).isEqualTo(1L);
    }

    @Test
    void Should_NotThrow_When_ResultIsNull() {
        // Act & Assert
        assertThatCode(() -> adapter.append(null, SourceType.CONFLUENCE))
                .doesNotThrowAnyException();

        verify(eventRepository, never()).save(any());
    }

    @Test
    void Should_NotThrow_When_ScanIdIsBlank() {
        // Arrange
        ContentScanResult result = ContentScanResult.builder()
                .scanId("")
                .eventType("CONTENT_ITEM")
                .build();

        // Act & Assert
        assertThatCode(() -> adapter.append(result, SourceType.CONFLUENCE))
                .doesNotThrowAnyException();

        verify(eventRepository, never()).save(any());
    }

    @Test
    void Should_NotThrow_When_EventTypeIsBlank() {
        // Arrange
        ContentScanResult result = ContentScanResult.builder()
                .scanId("scan-1")
                .eventType("")
                .build();

        // Act & Assert
        assertThatCode(() -> adapter.append(result, SourceType.CONFLUENCE))
                .doesNotThrowAnyException();

        verify(eventRepository, never()).save(any());
    }

    @Test
    void Should_IncrementSequence_When_MultipleEventsAppended() {
        // Arrange
        ContentScanResult result = ContentScanResult.builder()
                .scanId("scan-1")
                .sourceId("SPACE1")
                .eventType("CONTENT_ITEM")
                .emittedAt(Instant.now().toString())
                .build();

        when(scanResultEncryptor.encrypt(any())).thenReturn(result);
        when(eventRepository.findMaxEventSeqByScanId("scan-1")).thenReturn(0L);

        // Act
        adapter.append(result, SourceType.CONFLUENCE);
        adapter.append(result, SourceType.CONFLUENCE);

        // Assert
        ArgumentCaptor<ScanEventEntity> captor = ArgumentCaptor.forClass(ScanEventEntity.class);
        verify(eventRepository, times(2)).save(captor.capture());

        assertThat(captor.getAllValues().get(0).getEventSeq()).isEqualTo(1L);
        assertThat(captor.getAllValues().get(1).getEventSeq()).isEqualTo(2L);
    }

    @Test
    void Should_DeleteAllEvents_When_DeleteAllCalled() {
        // Act
        adapter.deleteAll();

        // Assert
        verify(eventRepository).deleteAllInBatch();
    }

    @Test
    void Should_HandleNullSourceType_When_Appending() {
        // Arrange
        ContentScanResult result = ContentScanResult.builder()
                .scanId("scan-1")
                .sourceId("SPACE1")
                .eventType("CONTENT_ITEM")
                .emittedAt(Instant.now().toString())
                .build();

        when(scanResultEncryptor.encrypt(any())).thenReturn(result);
        when(eventRepository.findMaxEventSeqByScanId("scan-1")).thenReturn(0L);

        // Act
        adapter.append(result, null);

        // Assert
        ArgumentCaptor<ScanEventEntity> captor = ArgumentCaptor.forClass(ScanEventEntity.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getSourceType()).isNull();
    }
}
