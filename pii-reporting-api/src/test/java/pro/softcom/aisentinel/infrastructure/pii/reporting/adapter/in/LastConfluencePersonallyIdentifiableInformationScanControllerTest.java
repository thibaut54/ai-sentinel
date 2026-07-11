package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pro.softcom.aisentinel.application.pii.reporting.DashboardFilterCriteria;
import pro.softcom.aisentinel.application.pii.reporting.port.in.ScanReportingPort;
import pro.softcom.aisentinel.domain.pii.reporting.DashboardFacets;
import pro.softcom.aisentinel.domain.pii.reporting.ScanReportingSummary;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;
import pro.softcom.aisentinel.domain.pii.reporting.SpaceSummary;
import pro.softcom.aisentinel.infrastructure.config.SecurityConfig;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.DashboardFacetsDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.FacetCountDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanReportingSummaryDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SeverityCountsDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SpaceSummaryDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ConfluenceContentScanResultToScanEventMapper;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.LastScanMapper;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.ScanReportingSummaryMapper;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper.SpaceStatusMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LastConfluencePersonallyIdentifiableInformationScanController.class)
@Import(SecurityConfig.class)
class LastConfluencePersonallyIdentifiableInformationScanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScanReportingPort scanReportingPort;

    @MockitoBean
    private LastScanMapper lastScanMapper;

    @MockitoBean
    private SpaceStatusMapper spaceStatusMapper;

    @MockitoBean
    private ConfluenceContentScanResultToScanEventMapper confluenceContentScanResultToScanEventMapper;

    @MockitoBean
    private ScanReportingSummaryMapper scanReportingSummaryMapper;

    private static SpaceSummary space(String key, String status, String name) {
        return new SpaceSummary(key, status, 100.0, 10L, 5L,
                Instant.parse("2025-01-15T09:30:00Z"), name, SeverityCounts.zero(), Map.of(), "scan-123");
    }

    private static ScanReportingSummary summary(String scanId, int count, List<SpaceSummary> spaces) {
        return new ScanReportingSummary(scanId, Instant.parse("2025-01-15T10:00:00Z"), count, spaces,
                DashboardFacets.empty());
    }

    @Test
    void should_ReturnSpacesSummaryWithSeverityCounts_When_ScanExists() throws Exception {
        // Arrange
        String scanId = "scan-123";
        Instant lastUpdated = Instant.parse("2025-01-15T10:00:00Z");
        Instant lastEventTs = Instant.parse("2025-01-15T09:30:00Z");

        ScanReportingSummary domainSummary = summary(scanId, 2,
                List.of(space("SPACE1", "COMPLETED", "Space One"), space("SPACE2", "RUNNING", "Space Two")));

        ScanReportingSummaryDto dto = new ScanReportingSummaryDto(
                scanId, lastUpdated, 2,
                List.of(
                        new SpaceSummaryDto("SPACE1", "COMPLETED", 100.0, 10L, 5L, lastEventTs,
                                new SeverityCountsDto(5, 10, 15, 30), "Space One", Map.of("EMAIL", 5), "scan-123"),
                        new SpaceSummaryDto("SPACE2", "RUNNING", 50.0, 5L, 2L, lastEventTs,
                                new SeverityCountsDto(2, 8, 12, 22), "Space Two", Map.of("PHONE_NUMBER", 2), "scan-123")),
                DashboardFacetsDto_empty());

        when(scanReportingPort.getGlobalScanSummary(any())).thenReturn(Optional.of(domainSummary));
        when(scanReportingSummaryMapper.toDto(domainSummary)).thenReturn(dto);

        // Act & Assert
        mockMvc.perform(get("/api/v1/scans/dashboard/spaces-summary")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.scanId").value(scanId))
                .andExpect(jsonPath("$.spacesCount").value(2))
                .andExpect(jsonPath("$.spaces.length()").value(2))
                .andExpect(jsonPath("$.spaces[0].severityCounts.total").value(30))
                .andExpect(jsonPath("$.spaces[1].severityCounts.total").value(22));

        verify(scanReportingPort).getGlobalScanSummary(any());
        verify(scanReportingSummaryMapper).toDto(domainSummary);
    }

    @Test
    void should_PassFilterCriteria_When_QueryParamsProvided() throws Exception {
        // Arrange
        ScanReportingSummary domainSummary = summary("scan-x", 0, List.of());
        ScanReportingSummaryDto dto = new ScanReportingSummaryDto(
                "scan-x", Instant.parse("2025-01-15T10:00:00Z"), 0, List.of(), DashboardFacetsDto_empty());

        when(scanReportingPort.getGlobalScanSummary(any())).thenReturn(Optional.of(domainSummary));
        when(scanReportingSummaryMapper.toDto(domainSummary)).thenReturn(dto);

        // Act
        mockMvc.perform(get("/api/v1/scans/dashboard/spaces-summary")
                        .param("piiTypes", "EMAIL,PHONE_NUMBER")
                        .param("severities", "HIGH")
                        .param("statuses", "OK")
                        .param("q", "marketing")
                        .param("sort", "totalDetections")
                        .param("order", "asc")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Assert criteria binding
        verify(scanReportingPort).getGlobalScanSummary(argThat((DashboardFilterCriteria criteria) ->
                criteria.piiTypes().equals(List.of("EMAIL", "PHONE_NUMBER"))
                        && criteria.severities().equals(List.of("HIGH"))
                        && criteria.statuses().equals(List.of("OK"))
                        && "marketing".equals(criteria.search())
                        && "totalDetections".equals(criteria.sort())
                        && "asc".equals(criteria.order())));
    }

    @Test
    void should_ExposeFacets_When_ResponseIsSuccessful() throws Exception {
        // Arrange
        String scanId = "scan-facets";
        Instant lastUpdated = Instant.parse("2025-01-15T12:00:00Z");

        ScanReportingSummary domainSummary = summary(scanId, 1, List.of(space("S", "COMPLETED", "S")));

        DashboardFacetsDto facetsDto = new DashboardFacetsDto(
                Map.of("EMAIL", new FacetCountDto(3, 12)),
                Map.of("HIGH", new FacetCountDto(2, 5)),
                Map.of("OK", new FacetCountDto(4, 17)));

        ScanReportingSummaryDto dto = new ScanReportingSummaryDto(
                scanId, lastUpdated, 1,
                List.of(new SpaceSummaryDto("S", "COMPLETED", 100.0, 20L, 10L, lastUpdated,
                        new SeverityCountsDto(3, 7, 11, 21), "S", Map.of("IBAN_CODE", 4), "scan-facets")),
                facetsDto);

        when(scanReportingPort.getGlobalScanSummary(any())).thenReturn(Optional.of(domainSummary));
        when(scanReportingSummaryMapper.toDto(domainSummary)).thenReturn(dto);

        // Act & Assert
        mockMvc.perform(get("/api/v1/scans/dashboard/spaces-summary")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facets.piiTypes.EMAIL.spaceCount").value(3))
                .andExpect(jsonPath("$.facets.piiTypes.EMAIL.totalOccurrences").value(12))
                .andExpect(jsonPath("$.facets.severities.HIGH.spaceCount").value(2))
                .andExpect(jsonPath("$.facets.statuses.OK.totalOccurrences").value(17))
                .andExpect(jsonPath("$.spaces[0].piiTypeCounts.IBAN_CODE").value(4));
    }

    @Test
    void should_ReturnNoContent_When_NoScanSummaryExists() throws Exception {
        // Arrange
        when(scanReportingPort.getGlobalScanSummary(any())).thenReturn(Optional.empty());

        // Act & Assert
        mockMvc.perform(get("/api/v1/scans/dashboard/spaces-summary")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        verify(scanReportingPort).getGlobalScanSummary(any());
    }

    private static DashboardFacetsDto DashboardFacetsDto_empty() {
        return new DashboardFacetsDto(Map.of(), Map.of(), Map.of());
    }
}
