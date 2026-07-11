package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pro.softcom.aisentinel.application.pii.reporting.DashboardFilterCriteria;
import pro.softcom.aisentinel.application.pii.reporting.port.in.ScanReportingPort;
import pro.softcom.aisentinel.domain.pii.scan.ConfluenceSpaceScanState;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ConfluenceContentScanResultEventDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.LastScanDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanReportingSummaryDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SpaceScanStateDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ConfluenceContentScanResultToScanEventMapper;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.LastScanMapper;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ScanReportingSummaryMapper;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.SpaceStatusMapper;

import java.util.List;

@RestController
@RequestMapping("/api/v1/scans")
@RequiredArgsConstructor
public class LastConfluencePersonallyIdentifiableInformationScanController {

    private final ScanReportingPort scanReportingPort;
    private final LastScanMapper lastScanMapper;
    private final SpaceStatusMapper spaceStatusMapper;
    private final ConfluenceContentScanResultToScanEventMapper confluenceContentScanResultToScanEventMapper;
    private final ScanReportingSummaryMapper scanReportingSummaryMapper;

    @GetMapping("/last")
    public ResponseEntity<@NonNull LastScanDto> getLastScan() {
        return scanReportingPort.getLatestScan()
                .map(lastScanMapper::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/last/spaces")
    public ResponseEntity<@NonNull List<SpaceScanStateDto>> getLastScanSpaceStatuses() {
        return scanReportingPort.getLatestScan()
                .map(meta -> {
                    List<ConfluenceSpaceScanState> latestSpaceScanStates = scanReportingPort.getLatestSpaceScanStateList(
                        meta.scanId());
                    return ResponseEntity.ok(spaceStatusMapper.toDtoList(latestSpaceScanStates));
                })
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/last/items")
    public ResponseEntity<@NonNull List<ConfluenceContentScanResultEventDto>> getLastScanItems() {
        List<ConfluenceContentScanResultEventDto> items = scanReportingPort.getGlobalScanItemsEncrypted().stream()
                .map(confluenceContentScanResultToScanEventMapper::toDto)
                .toList();
        if (items.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(items);
    }

    /**
     * Authoritative, fully server-side filtered/sorted/searched dashboard source over ALL spaces,
     * combining authoritative status/progress from scan_checkpoints with aggregated counters from
     * scan_events, plus contextual facet counts.
     *
     * <p>All query parameters are optional; an absent parameter means "no constraint".
     *
     * @param piiTypes   comma-separated PII type codes (e.g. EMAIL,PHONE_NUMBER)
     * @param severities comma-separated severities (HIGH|MEDIUM|LOW)
     * @param statuses   comma-separated UI status codes (NOT_STARTED|PENDING|PAUSED|RUNNING|OK|FAILED|INTERRUPTED)
     * @param q          free-text search on space name or key (case-insensitive)
     * @param sort       sort criterion (name|totalDetections|severityScore|lastScan|piiType:&lt;CODE&gt;)
     * @param order      sort direction (asc|desc)
     * @return the filtered/sorted summary with facets, or 204 when no data exists
     */
    @GetMapping("/dashboard/spaces-summary")
    public ResponseEntity<@NonNull ScanReportingSummaryDto> getDashboardSpacesSummary(
            @RequestParam(required = false) List<String> piiTypes,
            @RequestParam(required = false) List<String> severities,
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order) {
        DashboardFilterCriteria criteria = new DashboardFilterCriteria(
                piiTypes, severities, statuses, q, sort, order);
        return scanReportingPort.getGlobalScanSummary(criteria)
                .map(scanReportingSummaryMapper::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}