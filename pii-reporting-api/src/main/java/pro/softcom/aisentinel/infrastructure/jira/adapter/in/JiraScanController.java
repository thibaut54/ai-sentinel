package pro.softcom.aisentinel.infrastructure.jira.adapter.in;

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
import pro.softcom.aisentinel.application.jira.port.in.StreamJiraScanPort;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ContentScanResultEventDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanEventType;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ConfluenceContentScanResultToScanEventMapper;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * SSE WebFlux controller that streams Jira scan results as Server-Sent Events.
 * Mirrors the Confluence scan controller pattern but for Jira projects.
 */
@RestController
@RequestMapping("/api/v1/stream/jira")
@Tag(name = "Jira Streaming (WebFlux)", description = "Jira scan streaming via SSE (WebFlux)")
@RequiredArgsConstructor
@Slf4j
public class JiraScanController {

    private final StreamJiraScanPort streamJiraScanPort;
    private final ConfluenceContentScanResultToScanEventMapper mapper;

    @GetMapping(value = "/projects/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream scan of all Jira projects (SSE)")
    @ApiResponse(responseCode = "200", description = "SSE stream started")
    public Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> streamAllProjectsScan() {
        log.info("[SSE][Jira] Starting multi-project stream");

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> keepalive = buildKeepalive();

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> data = streamJiraScanPort.scanAllProjects()
                .delaySubscription(Duration.ofMillis(50))
                .map(ev -> ServerSentEvent.<ContentScanResultEventDto>builder()
                        .event(ev.eventType())
                        .data(mapper.toDto(ev))
                        .build());

        return Flux.merge(data, keepalive)
                .doFinally(sig -> log.info("[SSE][Jira] Connection closed for all projects scan (signal={})", sig));
    }

    @GetMapping(value = "/projects/events/selected", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream scan of selected Jira projects (SSE)")
    @ApiResponse(responseCode = "200", description = "SSE stream started")
    public Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> streamSelectedProjectsScan(
            @Parameter(description = "List of project keys to scan") @RequestParam List<String> projectKeys
    ) {
        log.info("[SSE][Jira] Starting multi-project stream for selected projects: {}", projectKeys);

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> keepalive = buildKeepalive();

        Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> data = streamJiraScanPort.scanSelectedProjects(projectKeys)
                .delaySubscription(Duration.ofMillis(50))
                .map(ev -> ServerSentEvent.<ContentScanResultEventDto>builder()
                        .event(ev.eventType())
                        .data(mapper.toDto(ev))
                        .build());

        return Flux.merge(data, keepalive)
                .doFinally(sig -> log.info("[SSE][Jira] Connection closed for selected projects scan (signal={})", sig));
    }

    private Flux<ServerSentEvent<@NonNull ContentScanResultEventDto>> buildKeepalive() {
        return Flux.interval(Duration.ofSeconds(15))
                .map(ignored -> ServerSentEvent.<ContentScanResultEventDto>builder()
                        .event(ScanEventType.KEEPALIVE.toJson())
                        .comment("ping")
                        .build());
    }
}
