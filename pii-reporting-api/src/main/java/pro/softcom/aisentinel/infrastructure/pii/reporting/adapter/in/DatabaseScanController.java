package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pro.softcom.aisentinel.application.pii.reporting.port.in.StreamDatabaseScanPort;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.SourceType;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ConfluenceContentScanResultEventDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanEventType;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ConfluenceContentScanResultToScanEventMapper;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

/**
 * Endpoint for streaming database scan results.
 * Reuses existing DTOs and Mappers for consistency with the frontend.
 */
@RestController
@RequestMapping("/api/v1/scan/database")
@Tag(name = "Database Scanning", description = "Stream database scan results via SSE")
@RequiredArgsConstructor
@Slf4j
public class DatabaseScanController {

    private final StreamDatabaseScanPort streamDatabaseScanPort;
    private final ConfluenceContentScanResultToScanEventMapper mapper;

    @Value("${scan.datasource.url}")
    private String dbUrl;
    @Value("${scan.datasource.username}")
    private String dbUsername;
    @Value("${scan.datasource.password}")
    private String dbPassword;

    @GetMapping(value = "/{sourceId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream database scan (SSE)")
    @ApiResponse(responseCode = "200", description = "SSE stream started")
    public Flux<ServerSentEvent<@NonNull ConfluenceContentScanResultEventDto>> streamScan(
            @Parameter(description = "Source ID of the database content to scan") @PathVariable String sourceId
    ) {
        log.info("[SSE][DB] Starting stream for sourceId {}", sourceId);

        // Assume internal DB scan for backward compatibility/simplicity for now
        ScanSourceConfig config = new ScanSourceConfig(
                SourceType.POSTGRES,
                Map.of(
                        "url", dbUrl,
                        "username", dbUsername,
                        "password", dbPassword,
                        "table", sourceId
                )
        );

        Flux<ServerSentEvent<@NonNull ConfluenceContentScanResultEventDto>> keepalive = Flux.interval(Duration.ofSeconds(15))
                .map(ignored -> ServerSentEvent.<ConfluenceContentScanResultEventDto>builder()
                        .event(ScanEventType.KEEPALIVE.toJson())
                        .comment("ping")
                        .build());

        Flux<ServerSentEvent<@NonNull ConfluenceContentScanResultEventDto>> data = streamDatabaseScanPort.streamScan(config)
                .map(ev -> ServerSentEvent.<ConfluenceContentScanResultEventDto>builder()
                        .event(ev.eventType())
                        .data(mapper.toDto(ev))
                        .build());

        return Flux.merge(data, keepalive)
                .doFinally(sig -> log.info("[SSE][DB] Connection closed for sourceId {} (signal={})", sourceId, sig));
    }
}
