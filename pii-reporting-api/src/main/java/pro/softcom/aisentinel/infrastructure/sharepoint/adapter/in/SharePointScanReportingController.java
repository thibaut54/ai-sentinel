package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in;

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
@RequestMapping("/api/v1/sharepoint/scans")
@RequiredArgsConstructor
public class SharePointScanReportingController {

    private final ScanReportingPort scanReportingPort;
    private final LastScanMapper lastScanMapper;
    private final ScanReportingSummaryMapper scanReportingSummaryMapper;
    private final ConfluenceContentScanResultToScanEventMapper itemMapper;

    @GetMapping("/last")
    public ResponseEntity<@NonNull LastScanDto> getLastSharePointScan() {
        return scanReportingPort.getLatestScanBySourceType(SourceType.SHAREPOINT)
                .map(lastScanMapper::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/dashboard/summary")
    public ResponseEntity<@NonNull ScanReportingSummaryDto> getSharePointDashboardSummary() {
        return scanReportingPort.getScanSummaryBySourceType(SourceType.SHAREPOINT)
                .map(scanReportingSummaryMapper::toDto)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/last/items")
    public ResponseEntity<@NonNull List<ContentScanResultEventDto>> getSharePointLastScanItems() {
        List<ContentScanResultEventDto> items = scanReportingPort.getScanItemsBySourceType(SourceType.SHAREPOINT)
                .stream().map(itemMapper::toDto).toList();
        return items.isEmpty()
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(items);
    }
}
