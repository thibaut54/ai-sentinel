package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.reporting.service.PiiContextExtractor;
import pro.softcom.aisentinel.application.pii.security.ScanResultEncryptor;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.DetectionEventRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanEventEntity;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JpaScanEventStoreAdapter - event persistence and sequencing")
class JpaScanEventStoreAdapterTest {

    @Mock
    private DetectionEventRepository eventRepository;

    @Mock
    private ScanResultEncryptor scanResultEncryptor;

    @Mock
    private PiiContextExtractor piiContextExtractor;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Captor
    private ArgumentCaptor<ScanEventEntity> entityCaptor;

    @InjectMocks
    private JpaScanEventStoreAdapter adapter;

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static final String SCAN_ID = "scan-1";
    private static final String SPACE_KEY = "SPACE1";
    private static final String EVENT_TYPE = "item";
    private static final String PAGE_ID = "page-42";
    private static final String PAGE_TITLE = "My Page";
    private static final String ATTACHMENT_NAME = "doc.pdf";
    private static final String ATTACHMENT_TYPE = "application/pdf";
    private static final String EMITTED_AT = "2025-06-15T10:30:00Z";

    private ConfluenceContentScanResult buildResult() {
        return ConfluenceContentScanResult.builder()
                .scanId(SCAN_ID)
                .spaceKey(SPACE_KEY)
                .eventType(EVENT_TYPE)
                .pageId(PAGE_ID)
                .pageTitle(PAGE_TITLE)
                .attachmentName(ATTACHMENT_NAME)
                .attachmentType(ATTACHMENT_TYPE)
                .emittedAt(EMITTED_AT)
                .build();
    }

    // ── append ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("append")
    class Append {

        @Test
        @DisplayName("Should_EncryptAndPersistEntity_When_ValidScanResult")
        void Should_EncryptAndPersistEntity_When_ValidScanResult() {
            // Arrange
            ConfluenceContentScanResult result = buildResult();
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(0L);

            // Act
            adapter.append(result);

            // Assert
            verify(scanResultEncryptor).encrypt(result);
            verify(eventRepository).save(entityCaptor.capture());
            ScanEventEntity saved = entityCaptor.getValue();

            assertSoftly(softly -> {
                softly.assertThat(saved.getScanId()).isEqualTo(SCAN_ID);
                softly.assertThat(saved.getEventSeq()).isEqualTo(1L);
                softly.assertThat(saved.getSpaceKey()).isEqualTo(SPACE_KEY);
                softly.assertThat(saved.getEventType()).isEqualTo(EVENT_TYPE);
                softly.assertThat(saved.getPageId()).isEqualTo(PAGE_ID);
                softly.assertThat(saved.getPageTitle()).isEqualTo(PAGE_TITLE);
                softly.assertThat(saved.getAttachmentName()).isEqualTo(ATTACHMENT_NAME);
                softly.assertThat(saved.getAttachmentType()).isEqualTo(ATTACHMENT_TYPE);
                softly.assertThat(saved.getTs()).isEqualTo(Instant.parse(EMITTED_AT));
                softly.assertThat(saved.getPayload()).isNotNull();
                softly.assertThat(saved.getPayload().has("scanId")).isTrue();
            });
        }

        @Test
        @DisplayName("Should_UseInstantNow_When_EmittedAtIsNull")
        void Should_UseInstantNow_When_EmittedAtIsNull() {
            // Arrange
            ConfluenceContentScanResult result = ConfluenceContentScanResult.builder()
                    .scanId(SCAN_ID)
                    .spaceKey(SPACE_KEY)
                    .eventType(EVENT_TYPE)
                    .emittedAt(null)
                    .build();
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(0L);

            Instant before = Instant.now();

            // Act
            adapter.append(result);

            Instant after = Instant.now();

            // Assert
            verify(eventRepository).save(entityCaptor.capture());
            Instant ts = entityCaptor.getValue().getTs();
            assertThat(ts).isBetween(before, after);
        }

        @Test
        @DisplayName("Should_LogWarningAndNotSave_When_ScanResultIsNull")
        void Should_LogWarningAndNotSave_When_ScanResultIsNull() {
            // Act
            assertThatCode(() -> adapter.append(null))
                    .doesNotThrowAnyException();

            // Assert
            verify(eventRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should_LogWarningAndNotSave_When_ScanIdIsBlank")
        void Should_LogWarningAndNotSave_When_ScanIdIsBlank() {
            // Arrange
            ConfluenceContentScanResult result = ConfluenceContentScanResult.builder()
                    .scanId("   ")
                    .eventType(EVENT_TYPE)
                    .build();

            // Act
            assertThatCode(() -> adapter.append(result))
                    .doesNotThrowAnyException();

            // Assert
            verify(eventRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should_LogWarningAndNotSave_When_EventTypeIsBlank")
        void Should_LogWarningAndNotSave_When_EventTypeIsBlank() {
            // Arrange
            ConfluenceContentScanResult result = ConfluenceContentScanResult.builder()
                    .scanId(SCAN_ID)
                    .eventType("")
                    .build();

            // Act
            assertThatCode(() -> adapter.append(result))
                    .doesNotThrowAnyException();

            // Assert
            verify(eventRepository, never()).save(any());
        }

        @Test
        @DisplayName("Should_NotThrow_When_RepositorySaveThrowsException")
        void Should_NotThrow_When_RepositorySaveThrowsException() {
            // Arrange
            ConfluenceContentScanResult result = buildResult();
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(0L);
            when(eventRepository.save(any())).thenThrow(new RuntimeException("DB connection lost"));

            // Act & Assert
            assertThatCode(() -> adapter.append(result))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should_SerializePayloadAsJsonNode_When_ValidScanResult")
        void Should_SerializePayloadAsJsonNode_When_ValidScanResult() {
            // Arrange
            ConfluenceContentScanResult result = buildResult();
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(0L);

            // Act
            adapter.append(result);

            // Assert
            verify(eventRepository).save(entityCaptor.capture());
            JsonNode payload = entityCaptor.getValue().getPayload();

            assertSoftly(softly -> {
                softly.assertThat(payload.get("scanId").asText()).isEqualTo(SCAN_ID);
                softly.assertThat(payload.get("spaceKey").asText()).isEqualTo(SPACE_KEY);
                softly.assertThat(payload.get("eventType").asText()).isEqualTo(EVENT_TYPE);
                softly.assertThat(payload.get("pageId").asText()).isEqualTo(PAGE_ID);
            });
        }
    }

    // ── deleteAll ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("deleteAll")
    class DeleteAll {

        @Test
        @DisplayName("Should_DelegateToRepository_When_DeleteAllCalled")
        void Should_DelegateToRepository_When_DeleteAllCalled() {
            // Act
            adapter.deleteAll();

            // Assert
            verify(eventRepository).deleteAllInBatch();
        }
    }

    // ── nextSeq ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("nextSeq - sequence generation")
    class NextSeq {

        @Test
        @DisplayName("Should_InitializeFromDbAndReturnOne_When_FirstCallForScan")
        void Should_InitializeFromDbAndReturnOne_When_FirstCallForScan() {
            // Arrange
            ConfluenceContentScanResult result = buildResult();
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(0L);

            // Act
            adapter.append(result);

            // Assert
            verify(eventRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getEventSeq()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should_IncrementSequence_When_MultipleAppendsForSameScan")
        void Should_IncrementSequence_When_MultipleAppendsForSameScan() {
            // Arrange
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(0L);

            ConfluenceContentScanResult result1 = buildResult();
            ConfluenceContentScanResult result2 = buildResult();
            ConfluenceContentScanResult result3 = buildResult();

            // Act
            adapter.append(result1);
            adapter.append(result2);
            adapter.append(result3);

            // Assert
            verify(eventRepository, org.mockito.Mockito.times(3)).save(entityCaptor.capture());
            var captured = entityCaptor.getAllValues();

            assertSoftly(softly -> {
                softly.assertThat(captured.get(0).getEventSeq()).isEqualTo(1L);
                softly.assertThat(captured.get(1).getEventSeq()).isEqualTo(2L);
                softly.assertThat(captured.get(2).getEventSeq()).isEqualTo(3L);
            });
        }

        @Test
        @DisplayName("Should_ContinueFromDbMax_When_ExistingEventsInDb")
        void Should_ContinueFromDbMax_When_ExistingEventsInDb() {
            // Arrange
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(42L);

            ConfluenceContentScanResult result = buildResult();

            // Act
            adapter.append(result);

            // Assert
            verify(eventRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getEventSeq()).isEqualTo(43L);
        }

        @Test
        @DisplayName("Should_StartFromOne_When_DbQueryFails")
        void Should_StartFromOne_When_DbQueryFails() {
            // Arrange
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID))
                    .thenThrow(new RuntimeException("DB error"));

            ConfluenceContentScanResult result = buildResult();

            // Act
            adapter.append(result);

            // Assert
            verify(eventRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getEventSeq()).isEqualTo(1L);
        }

        @Test
        @DisplayName("Should_MaintainSeparateSequences_When_DifferentScanIds")
        void Should_MaintainSeparateSequences_When_DifferentScanIds() {
            // Arrange
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId("scan-A")).thenReturn(10L);
            when(eventRepository.findMaxEventSeqByScanId("scan-B")).thenReturn(20L);

            ConfluenceContentScanResult resultA = ConfluenceContentScanResult.builder()
                    .scanId("scan-A").spaceKey(SPACE_KEY).eventType(EVENT_TYPE).emittedAt(EMITTED_AT).build();
            ConfluenceContentScanResult resultB = ConfluenceContentScanResult.builder()
                    .scanId("scan-B").spaceKey(SPACE_KEY).eventType(EVENT_TYPE).emittedAt(EMITTED_AT).build();

            // Act
            adapter.append(resultA);
            adapter.append(resultB);

            // Assert
            verify(eventRepository, org.mockito.Mockito.times(2)).save(entityCaptor.capture());
            var captured = entityCaptor.getAllValues();

            assertSoftly(softly -> {
                softly.assertThat(captured.get(0).getScanId()).isEqualTo("scan-A");
                softly.assertThat(captured.get(0).getEventSeq()).isEqualTo(11L);
                softly.assertThat(captured.get(1).getScanId()).isEqualTo("scan-B");
                softly.assertThat(captured.get(1).getEventSeq()).isEqualTo(21L);
            });
        }
    }

    // ── parseInstant (tested indirectly via append) ──────────────────────────

    @Nested
    @DisplayName("parseInstant - timestamp parsing")
    class ParseInstant {

        @Test
        @DisplayName("Should_ParseTimestamp_When_ValidIsoString")
        void Should_ParseTimestamp_When_ValidIsoString() {
            // Arrange
            ConfluenceContentScanResult result = ConfluenceContentScanResult.builder()
                    .scanId(SCAN_ID).spaceKey(SPACE_KEY).eventType(EVENT_TYPE)
                    .emittedAt("2025-01-15T14:30:00Z")
                    .build();
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(0L);

            // Act
            adapter.append(result);

            // Assert
            verify(eventRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getTs())
                    .isEqualTo(Instant.parse("2025-01-15T14:30:00Z"));
        }

        @Test
        @DisplayName("Should_FallbackToNow_When_EmittedAtIsBlank")
        void Should_FallbackToNow_When_EmittedAtIsBlank() {
            // Arrange
            ConfluenceContentScanResult result = ConfluenceContentScanResult.builder()
                    .scanId(SCAN_ID).spaceKey(SPACE_KEY).eventType(EVENT_TYPE)
                    .emittedAt("   ")
                    .build();
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(0L);

            Instant before = Instant.now();

            // Act
            adapter.append(result);

            Instant after = Instant.now();

            // Assert
            verify(eventRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getTs()).isBetween(before, after);
        }

        @Test
        @DisplayName("Should_FallbackToNow_When_EmittedAtIsInvalidFormat")
        void Should_FallbackToNow_When_EmittedAtIsInvalidFormat() {
            // Arrange
            ConfluenceContentScanResult result = ConfluenceContentScanResult.builder()
                    .scanId(SCAN_ID).spaceKey(SPACE_KEY).eventType(EVENT_TYPE)
                    .emittedAt("not-a-date")
                    .build();
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(0L);

            Instant before = Instant.now();

            // Act
            adapter.append(result);

            Instant after = Instant.now();

            // Assert
            verify(eventRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getTs()).isBetween(before, after);
        }

        @Test
        @DisplayName("Should_FallbackToNow_When_EmittedAtIsNull")
        void Should_FallbackToNow_When_EmittedAtIsNull() {
            // Arrange
            ConfluenceContentScanResult result = ConfluenceContentScanResult.builder()
                    .scanId(SCAN_ID).spaceKey(SPACE_KEY).eventType(EVENT_TYPE)
                    .emittedAt(null)
                    .build();
            when(scanResultEncryptor.encrypt(any())).thenAnswer(inv -> inv.getArgument(0));
            when(eventRepository.findMaxEventSeqByScanId(SCAN_ID)).thenReturn(0L);

            Instant before = Instant.now();

            // Act
            adapter.append(result);

            Instant after = Instant.now();

            // Assert
            verify(eventRepository).save(entityCaptor.capture());
            assertThat(entityCaptor.getValue().getTs()).isBetween(before, after);
        }
    }
}
