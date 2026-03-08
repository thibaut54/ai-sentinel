package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pro.softcom.aisentinel.application.sharepoint.port.in.StreamSharePointScanPort;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ContentScanResultEventDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanEventType;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ConfluenceContentScanResultToScanEventMapper;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * SSE WebFlux controller that streams SharePoint scan results as Server-Sent Events.
 * Mirrors the Jira scan controller pattern but for SharePoint sites.
 */
@RestController
@RequestMapping("/api/v1/stream/sharepoint")
@Tag(name = "SharePoint Streaming (WebFlux)", description = "SharePoint scan streaming via SSE (WebFlux)")
@RequiredArgsConstructor
@Slf4j
public class SharePointScanController {

    private final StreamSharePointScanPort streamSharePointScanPort;
    private final ConfluenceContentScanResultToScanEventMapper mapper;

    @GetMapping(value = "/sites/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream scan of all SharePoint sites (SSE)")
    @ApiResponse(responseCode = "200", description = "SSE stream started")
    public Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> streamAllSitesScan() {
        log.info("[SSE][SharePoint] Starting multi-site stream");

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> keepalive = buildKeepalive();

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> data = streamSharePointScanPort.scanAllSites()
                .delaySubscription(Duration.ofMillis(50))
                .map(ev -> ServerSentEvent.<ContentScanResultEventDto>builder()
                        .event(ev.eventType())
                        .data(mapper.toDto(ev))
                        .build());

        return Flux.merge(data, keepalive)
                .doFinally(sig -> log.info("[SSE][SharePoint] Connection closed for all sites scan (signal={})", sig));
    }

    @GetMapping(value = "/sites/events/selected", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream scan of selected SharePoint sites (SSE)")
    @ApiResponse(responseCode = "200", description = "SSE stream started")
    public Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> streamSelectedSitesScan(
            @Parameter(description = "List of site IDs to scan") @RequestParam List<String> siteIds
    ) {
        log.info("[SSE][SharePoint] Starting multi-site stream for selected sites: {}", siteIds);

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> keepalive = buildKeepalive();

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> data = streamSharePointScanPort.scanSelectedSites(siteIds)
                .delaySubscription(Duration.ofMillis(50))
                .map(ev -> ServerSentEvent.<ContentScanResultEventDto>builder()
                        .event(ev.eventType())
                        .data(mapper.toDto(ev))
                        .build());

        return Flux.merge(data, keepalive)
                .doFinally(sig -> log.info("[SSE][SharePoint] Connection closed for selected sites scan (signal={})", sig));
    }

    private Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> buildKeepalive() {
        return Flux.interval(Duration.ofSeconds(15))
                .map(ignored -> ServerSentEvent.<ContentScanResultEventDto>builder()
                        .event(ScanEventType.KEEPALIVE.toJson())
                        .comment("ping")
                        .build());
    }
}
