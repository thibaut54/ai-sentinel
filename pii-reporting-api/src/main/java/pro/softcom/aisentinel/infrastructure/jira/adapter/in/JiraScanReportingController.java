package pro.softcom.aisentinel.infrastructure.jira.adapter.in;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pro.softcom.aisentinel.application.pii.reporting.port.in.ScanReportingPort;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ContentScanResultEventDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.LastScanDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanReportingSummaryDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ConfluenceContentScanResultToScanEventMapper;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.LastScanMapper;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ScanReportingSummaryMapper;

import java.util.List;

@RestController
@RequestMapping("/api/v1/jira/scans")
@RequiredArgsConstructor
public class JiraScanReportingController {

    private final ScanReportingPort scanReportingPort;
    private final LastScanMapper lastScanMapper;
    private final ScanReportingSummaryMapper scanReportingSummaryMapper;
    private final ConfluenceContentScanResultToScanEventMapper itemMapper;

    @GetMapping("/last")
    public ResponseEntity<@NonNull LastScanDto> getLastJiraScan() {
        return scanReportingPort.getLatestScanBySourceType(SourceType.JIRA)
                .map(lastScanMapper::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/dashboard/summary")
    public ResponseEntity<@NonNull ScanReportingSummaryDto> getJiraDashboardSummary() {
        return scanReportingPort.getScanSummaryBySourceType(SourceType.JIRA)
                .map(scanReportingSummaryMapper::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/last/items")
    public ResponseEntity<@NonNull List<ContentScanResultEventDto>> getJiraLastScanItems() {
        List<ContentScanResultEventDto> items = scanReportingPort.getScanItemsBySourceType(SourceType.JIRA)
                .stream().map(itemMapper::toDto).toList();
        return items.isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(items);
    }
}
