package pro.softcom.aisentinel.application.pii.reporting.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanTimeOutConfig;
import pro.softcom.aisentinel.application.pii.reporting.service.ContentScanOrchestrator;
import pro.softcom.aisentinel.application.pii.scan.port.out.LoadContentPort;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;
import pro.softcom.aisentinel.domain.pii.scan.model.DatabaseSourceType;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamDatabaseScanUseCaseTest {

    @Mock
    private LoadContentPort loadContentPort;

    @Mock
    private PiiDetectorClient piiDetectorClient;

    @Mock
    private ContentScanOrchestrator contentScanOrchestrator;

    @Mock
    private ScanTimeOutConfig scanTimeOutConfig;

    private StreamDatabaseScanUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new StreamDatabaseScanUseCase(loadContentPort, piiDetectorClient, contentScanOrchestrator, scanTimeOutConfig);
    }

    @Test
    void Should_EmitErrorEvent_When_NoContentFound() {
        // Arrange
        ScanSourceConfig config = new ScanSourceConfig(
                DatabaseSourceType.POSTGRES,
                Map.of("table", "my_table", "url", "jdbc:postgresql://localhost/db",
                        "username", "user", "password", "pass")
        );
        when(loadContentPort.loadContent(config)).thenReturn(Flux.empty());

        // Act
        Flux<ContentScanResult> result = useCase.streamScan(config);

        // Assert
        StepVerifier.create(result)
                .assertNext(event -> {
                    assertThat(event.eventType()).isEqualTo(DetectionReportingEventType.ERROR.getLabel());
                    assertThat(event.message()).contains("No content found");
                })
                .verifyComplete();
    }

    @Test
    void Should_EmitErrorEvent_When_LoadContentFails() {
        // Arrange
        ScanSourceConfig config = new ScanSourceConfig(
                DatabaseSourceType.POSTGRES,
                Map.of("table", "my_table", "url", "jdbc:postgresql://localhost/db",
                        "username", "user", "password", "pass")
        );
        when(loadContentPort.loadContent(config)).thenReturn(Flux.error(new RuntimeException("Connection refused")));

        // Act
        Flux<ContentScanResult> result = useCase.streamScan(config);

        // Assert
        StepVerifier.create(result)
                .assertNext(event -> {
                    assertThat(event.eventType()).isEqualTo(DetectionReportingEventType.ERROR.getLabel());
                    assertThat(event.message()).contains("Scan failed");
                    assertThat(event.message()).contains("Connection refused");
                })
                .verifyComplete();
    }

    @Test
    void Should_EmitStartItemCompleteEvents_When_ContentFound() {
        // Arrange
        ScanSourceConfig config = new ScanSourceConfig(
                DatabaseSourceType.POSTGRES,
                Map.of("table", "my_table", "url", "jdbc:postgresql://localhost/db",
                        "username", "user", "password", "pass")
        );

        ScannableContent content = new ScannableContent() {
            @Override public String getId() { return "row-1"; }
            @Override public String getContentBody() { return "Some text"; }
            @Override public String getTitle() { return "Row 1"; }
            @Override public String getSourceId() { return "my_table"; }
            @Override public Map<String, Object> getMetadata() { return Map.of(); }
        };

        when(loadContentPort.loadContent(config)).thenReturn(Flux.just(content));
        when(scanTimeOutConfig.getPiiDetection()).thenReturn(Duration.ofSeconds(30));

        ContentScanResult startEvent = ContentScanResult.builder()
                .scanId("scan-1").eventType("SCAN_START").build();
        ContentScanResult itemEvent = ContentScanResult.builder()
                .scanId("scan-1").eventType("CONTENT_ITEM").build();
        ContentScanResult completeEvent = ContentScanResult.builder()
                .scanId("scan-1").eventType("SCAN_COMPLETE").build();

        when(contentScanOrchestrator.createStartEvent(anyString(), eq("my_table"), eq(1), eq(0.0)))
                .thenReturn(startEvent);
        when(piiDetectorClient.analyzeContent("Some text"))
                .thenReturn(new ContentPiiDetection(null, null, null, null, List.of(), Map.of()));
        when(contentScanOrchestrator.calculateProgress(1, 1)).thenReturn(100.0);
        when(contentScanOrchestrator.createContentItemEvent(anyString(), eq("my_table"), eq(content),
                eq("Some text"), any(ContentPiiDetection.class), eq(100.0)))
                .thenReturn(itemEvent);
        when(contentScanOrchestrator.createCompleteEvent(anyString(), eq("my_table")))
                .thenReturn(completeEvent);

        // Act
        Flux<ContentScanResult> result = useCase.streamScan(config);

        // Assert
        StepVerifier.create(result)
                .assertNext(event -> assertThat(event.eventType()).isEqualTo("SCAN_START"))
                .assertNext(event -> assertThat(event.eventType()).isEqualTo("CONTENT_ITEM"))
                .assertNext(event -> assertThat(event.eventType()).isEqualTo("SCAN_COMPLETE"))
                .verifyComplete();
    }
}
