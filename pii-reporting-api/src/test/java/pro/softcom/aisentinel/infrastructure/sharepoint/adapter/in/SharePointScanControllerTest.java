package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.ServerSentEvent;
import pro.softcom.aisentinel.application.sharepoint.port.in.StreamSharePointScanPort;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ContentScanResultEventDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanEventType;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ConfluenceContentScanResultToScanEventMapper;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharePointScanControllerTest {

    @Mock
    private StreamSharePointScanPort streamSharePointScanPort;

    @Mock
    private ConfluenceContentScanResultToScanEventMapper mapper;

    private SharePointScanController controller;

    @BeforeEach
    void setUp() {
        controller = new SharePointScanController(streamSharePointScanPort, mapper);
    }

    @Test
    void Should_ReturnSseStream_When_StreamAllSitesScan() {
        // Arrange
        ContentScanResult scanResult = ContentScanResult.builder()
                .scanId("scan-1")
                .eventType("start")
                .build();
        ContentScanResultEventDto dto = ContentScanResultEventDto.builder()
                .scanId("scan-1")
                .eventType(ScanEventType.START)
                .build();
        when(streamSharePointScanPort.scanAllSites()).thenReturn(Flux.just(scanResult));
        when(mapper.toDto(any(ContentScanResult.class))).thenReturn(dto);

        // Act
        Flux<ServerSentEvent<ContentScanResultEventDto>> result = controller.streamAllSitesScan();

        // Assert
        StepVerifier.create(result.take(1))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("start");
                    assertThat(sse.data()).isNotNull();
                })
                .thenCancel()
                .verify();

        verify(streamSharePointScanPort).scanAllSites();
    }

    @Test
    void Should_ReturnSseStream_When_StreamSelectedSitesScan() {
        // Arrange
        List<String> siteIds = List.of("site-1", "site-2");
        ContentScanResult scanResult = ContentScanResult.builder()
                .scanId("scan-2")
                .eventType("start")
                .build();
        ContentScanResultEventDto dto = ContentScanResultEventDto.builder()
                .scanId("scan-2")
                .eventType(ScanEventType.START)
                .build();
        when(streamSharePointScanPort.scanSelectedSites(siteIds)).thenReturn(Flux.just(scanResult));
        when(mapper.toDto(any(ContentScanResult.class))).thenReturn(dto);

        // Act
        Flux<ServerSentEvent<ContentScanResultEventDto>> result = controller.streamSelectedSitesScan(siteIds);

        // Assert
        StepVerifier.create(result.take(1))
                .assertNext(sse -> assertThat(sse.event()).isEqualTo("start"))
                .thenCancel()
                .verify();

        verify(streamSharePointScanPort).scanSelectedSites(siteIds);
    }

    @Test
    void Should_EmitKeepalive_When_NoScanResults() {
        // Arrange
        when(streamSharePointScanPort.scanAllSites()).thenReturn(Flux.empty());

        // Act
        Flux<ServerSentEvent<ContentScanResultEventDto>> result = controller.streamAllSitesScan();

        // Assert - should produce keepalive events
        StepVerifier.create(result.take(1))
                .assertNext(sse -> assertThat(sse.comment()).isEqualTo("ping"))
                .thenCancel()
                .verify();
    }
}
