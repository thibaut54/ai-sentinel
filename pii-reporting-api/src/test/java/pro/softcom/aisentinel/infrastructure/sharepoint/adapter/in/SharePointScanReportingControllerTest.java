package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pro.softcom.aisentinel.infrastructure.config.SecurityConfig;
import pro.softcom.aisentinel.application.pii.reporting.port.in.ScanReportingPort;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.LastScanMeta;
import pro.softcom.aisentinel.domain.pii.reporting.ScanReportingSummary;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ContentScanResultEventDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.LastScanDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanReportingSummaryDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ConfluenceContentScanResultToScanEventMapper;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.LastScanMapper;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ScanReportingSummaryMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SharePointScanReportingController.class)
@Import(SecurityConfig.class)
class SharePointScanReportingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScanReportingPort scanReportingPort;

    @MockitoBean
    private LastScanMapper lastScanMapper;

    @MockitoBean
    private ScanReportingSummaryMapper scanReportingSummaryMapper;

    @MockitoBean
    private ConfluenceContentScanResultToScanEventMapper itemMapper;

    @Test
    void Should_ReturnSharePointScanMeta_When_ScanExists() throws Exception {
        LastScanMeta meta = new LastScanMeta("sp-1", Instant.parse("2024-01-01T10:00:00Z"), 2);
        LastScanDto dto = new LastScanDto("sp-1", Instant.parse("2024-01-01T10:00:00Z"), 2);

        when(scanReportingPort.getLatestScanBySourceType(SourceType.SHAREPOINT))
            .thenReturn(Optional.of(meta));
        when(lastScanMapper.toDto(meta)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/sharepoint/scans/last"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scanId").value("sp-1"));
    }

    @Test
    void Should_ReturnNoContent_When_NoSharePointScanExists() throws Exception {
        when(scanReportingPort.getLatestScanBySourceType(SourceType.SHAREPOINT))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/sharepoint/scans/last"))
            .andExpect(status().isNoContent());
    }

    @Test
    void Should_ReturnSharePointSummary_When_SummaryExists() throws Exception {
        ScanReportingSummary summary = new ScanReportingSummary(
            "sp-1", Instant.parse("2024-01-01T10:00:00Z"), 1, List.of());
        ScanReportingSummaryDto dto = new ScanReportingSummaryDto(
            "sp-1", Instant.parse("2024-01-01T10:00:00Z"), 1, List.of());

        when(scanReportingPort.getScanSummaryBySourceType(SourceType.SHAREPOINT))
            .thenReturn(Optional.of(summary));
        when(scanReportingSummaryMapper.toDto(summary)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/sharepoint/scans/dashboard/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scanId").value("sp-1"));
    }

    @Test
    void Should_ReturnNoContent_When_NoSharePointSummary() throws Exception {
        when(scanReportingPort.getScanSummaryBySourceType(SourceType.SHAREPOINT))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/sharepoint/scans/dashboard/summary"))
            .andExpect(status().isNoContent());
    }

    @Test
    void Should_ReturnSharePointItems_When_ItemsExist() throws Exception {
        ContentScanResult result = ContentScanResult.builder()
            .scanId("sp-1").sourceId("SITE-1").eventType("item").build();
        ContentScanResultEventDto dto = ContentScanResultEventDto.builder()
            .scanId("sp-1").spaceKey("SITE-1").build();

        when(scanReportingPort.getScanItemsBySourceType(SourceType.SHAREPOINT))
            .thenReturn(List.of(result));
        when(itemMapper.toDto(result)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/sharepoint/scans/last/items"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].scanId").value("sp-1"));
    }

    @Test
    void Should_ReturnNoContent_When_NoSharePointItems() throws Exception {
        when(scanReportingPort.getScanItemsBySourceType(SourceType.SHAREPOINT))
            .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/sharepoint/scans/last/items"))
            .andExpect(status().isNoContent());
    }
}
