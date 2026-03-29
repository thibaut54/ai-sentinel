package pro.softcom.aisentinel.infrastructure.jira.adapter.in;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import pro.softcom.aisentinel.application.jira.port.in.StreamJiraResumeScanPort;
import pro.softcom.aisentinel.application.jira.port.in.StreamJiraScanPort;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ContentScanResultEventDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanEventType;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ConfluenceContentScanResultToScanEventMapper;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraScanController")
class JiraScanControllerTest {

    @Mock
    private StreamJiraScanPort streamJiraScanPort;

    @Mock
    private StreamJiraResumeScanPort streamJiraResumeScanPort;

    private JiraScanController controller;

    @BeforeEach
    void setUp() {
        controller = new JiraScanController(
                streamJiraScanPort,
                streamJiraResumeScanPort,
                new ConfluenceContentScanResultToScanEventMapper()
        );
    }

    @Test
    @DisplayName("Should_StreamAllProjectEvents_When_ScanProducesEvents")
    void Should_StreamAllProjectEvents_When_ScanProducesEvents() {
        Flux<ContentScanResult> events = Flux.just(
                ContentScanResult.builder().eventType(ScanEventType.MULTI_START.toJson()).build(),
                ContentScanResult.builder().eventType(ScanEventType.START.toJson()).build(),
                ContentScanResult.builder().eventType(ScanEventType.ITEM.toJson()).build(),
                ContentScanResult.builder().eventType(ScanEventType.COMPLETE.toJson()).build(),
                ContentScanResult.builder().eventType(ScanEventType.MULTI_COMPLETE.toJson()).build()
        );
        when(streamJiraScanPort.scanAllProjects()).thenReturn(events);

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> flux = controller.streamAllProjectsScan(null)
                .filter(sse -> !ScanEventType.KEEPALIVE.toJson().equals(sse.event()))
                .take(5)
                .timeout(Duration.ofSeconds(5));

        StepVerifier.create(flux)
                .expectNextMatches(sse -> ScanEventType.MULTI_START.toJson().equals(sse.event()))
                .expectNextMatches(sse -> ScanEventType.START.toJson().equals(sse.event()))
                .expectNextMatches(sse -> ScanEventType.ITEM.toJson().equals(sse.event()))
                .expectNextMatches(sse -> ScanEventType.COMPLETE.toJson().equals(sse.event()))
                .expectNextMatches(sse -> ScanEventType.MULTI_COMPLETE.toJson().equals(sse.event()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should_StreamSelectedProjectEvents_When_ProjectKeysProvided")
    void Should_StreamSelectedProjectEvents_When_ProjectKeysProvided() {
        Flux<ContentScanResult> events = Flux.just(
                ContentScanResult.builder().eventType(ScanEventType.MULTI_START.toJson()).build(),
                ContentScanResult.builder().eventType(ScanEventType.MULTI_COMPLETE.toJson()).build()
        );
        when(streamJiraScanPort.scanSelectedProjects(anyList())).thenReturn(events);

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> flux = controller
                .streamSelectedProjectsScan(List.of("PROJ1", "PROJ2"))
                .filter(sse -> !ScanEventType.KEEPALIVE.toJson().equals(sse.event()))
                .take(2)
                .timeout(Duration.ofSeconds(5));

        StepVerifier.create(flux)
                .expectNextMatches(sse -> ScanEventType.MULTI_START.toJson().equals(sse.event()))
                .expectNextMatches(sse -> ScanEventType.MULTI_COMPLETE.toJson().equals(sse.event()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should_EmitError_When_ScanProducesError")
    void Should_EmitError_When_ScanProducesError() {
        when(streamJiraScanPort.scanAllProjects()).thenReturn(Flux.just(
                ContentScanResult.builder()
                        .eventType(ScanEventType.ERROR.toJson())
                        .message("Scan error occurred")
                        .build()
        ));

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> flux = controller.streamAllProjectsScan(null)
                .filter(sse -> ScanEventType.ERROR.toJson().equals(sse.event()))
                .take(1)
                .timeout(Duration.ofSeconds(3));

        StepVerifier.create(flux)
                .assertNext(sse -> {
                    assertThat(sse.data()).isNotNull();
                    assertThat(sse.data().eventType()).isEqualTo(ScanEventType.ERROR);
                    assertThat(sse.data().message()).isEqualTo("Scan error occurred");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should_EmitKeepalive_When_NoDataEvents")
    void Should_EmitKeepalive_When_NoDataEvents() {
        when(streamJiraScanPort.scanAllProjects()).thenReturn(Flux.never());

        StepVerifier.withVirtualTime(() ->
                        controller.streamAllProjectsScan(null)
                                .filter(sse -> ScanEventType.KEEPALIVE.toJson().equals(sse.event()))
                                .take(1)
                )
                .thenAwait(Duration.ofSeconds(16))
                .expectNextMatches(sse -> ScanEventType.KEEPALIVE.toJson().equals(sse.event()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should_MapSseEventData_When_ScanResultHasContent")
    void Should_MapSseEventData_When_ScanResultHasContent() {
        ContentScanResult result = ContentScanResult.builder()
                .scanId("scan-123")
                .sourceId("PROJ")
                .eventType(ScanEventType.ITEM.toJson())
                .contentId("PROJ-42")
                .contentTitle("Fix important bug")
                .contentTotal(100)
                .contentIndex(5)
                .build();

        when(streamJiraScanPort.scanAllProjects()).thenReturn(Flux.just(result));

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> flux = controller.streamAllProjectsScan(null)
                .filter(sse -> ScanEventType.ITEM.toJson().equals(sse.event()))
                .take(1)
                .timeout(Duration.ofSeconds(5));

        StepVerifier.create(flux)
                .assertNext(sse -> {
                    assertThat(sse.data()).isNotNull();
                    assertThat(sse.data().scanId()).isEqualTo("scan-123");
                    assertThat(sse.data().spaceKey()).isEqualTo("PROJ");
                    assertThat(sse.data().pageId()).isEqualTo("PROJ-42");
                    assertThat(sse.data().pageTitle()).isEqualTo("Fix important bug");
                    assertThat(sse.data().pagesTotal()).isEqualTo(100);
                    assertThat(sse.data().pageIndex()).isEqualTo(5);
                })
                .verifyComplete();
    }
}
