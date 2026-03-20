package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import pro.softcom.aisentinel.application.pii.reporting.port.in.StreamDatabaseScanPort;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ContentScanResultEventDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanEventType;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ConfluenceContentScanResultToScanEventMapper;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseScanControllerTest {

    @Mock
    private StreamDatabaseScanPort streamDatabaseScanPort;

    @Mock
    private ConfluenceContentScanResultToScanEventMapper mapper;

    private DatabaseScanController controller;

    @BeforeEach
    void setUp() {
        controller = new DatabaseScanController(streamDatabaseScanPort, mapper);
        ReflectionTestUtils.setField(controller, "dbUrl", "jdbc:postgresql://localhost/testdb");
        ReflectionTestUtils.setField(controller, "dbUsername", "testuser");
        ReflectionTestUtils.setField(controller, "dbPassword", "testpass");
    }

    @Test
    void Should_ReturnSseStream_When_StreamScanCalled() {
        // Arrange
        ContentScanResult scanResult = ContentScanResult.builder()
                .scanId("scan-1")
                .eventType("start")
                .build();
        ContentScanResultEventDto dto = ContentScanResultEventDto.builder()
                .scanId("scan-1")
                .eventType(ScanEventType.START)
                .build();
        when(streamDatabaseScanPort.streamScan(any(ScanSourceConfig.class))).thenReturn(Flux.just(scanResult));
        when(mapper.toDto(any(ContentScanResult.class))).thenReturn(dto);

        // Act
        Flux<ServerSentEvent<ContentScanResultEventDto>> result = controller.streamScan("my_table");

        // Assert
        StepVerifier.create(result.take(1))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("start");
                    assertThat(sse.data()).isNotNull();
                    assertThat(sse.data().scanId()).isEqualTo("scan-1");
                })
                .thenCancel()
                .verify();
    }

    @Test
    void Should_PassSourceConfigWithTable_When_StreamScanCalled() {
        // Arrange
        ArgumentCaptor<ScanSourceConfig> configCaptor = ArgumentCaptor.forClass(ScanSourceConfig.class);
        when(streamDatabaseScanPort.streamScan(any(ScanSourceConfig.class))).thenReturn(Flux.empty());

        // Act
        controller.streamScan("users_table");

        // Assert
        verify(streamDatabaseScanPort).streamScan(configCaptor.capture());
        assertThat(configCaptor.getValue().properties())
                .containsEntry("table", "users_table")
                .containsEntry("url", "jdbc:postgresql://localhost/testdb")
                .containsEntry("username", "testuser")
                .containsEntry("password", "testpass");
    }

    @Test
    void Should_EmitKeepalive_When_NoScanResults() {
        // Arrange
        when(streamDatabaseScanPort.streamScan(any(ScanSourceConfig.class))).thenReturn(Flux.empty());

        // Act
        Flux<ServerSentEvent<ContentScanResultEventDto>> result = controller.streamScan("my_table");

        // Assert
        StepVerifier.create(result.take(1))
                .assertNext(sse -> assertThat(sse.comment()).isEqualTo("ping"))
                .thenCancel()
                .verify();
    }
}
