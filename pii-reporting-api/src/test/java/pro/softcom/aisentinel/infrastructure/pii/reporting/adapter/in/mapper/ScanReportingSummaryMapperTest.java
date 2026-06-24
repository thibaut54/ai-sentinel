package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.reporting.DashboardFacets;
import pro.softcom.aisentinel.domain.pii.reporting.FacetCount;
import pro.softcom.aisentinel.domain.pii.reporting.ScanReportingSummary;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;
import pro.softcom.aisentinel.domain.pii.reporting.SpaceSummary;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.ScanReportingSummaryDto;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto.SpaceSummaryDto;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScanReportingSummaryMapper}.
 *
 * <p>Verifies the transformation of domain objects into REST DTOs, including severity/PII-type
 * counts (now read from the domain) and the contextual facets.
 */
class ScanReportingSummaryMapperTest {

    private ScanReportingSummaryMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ScanReportingSummaryMapper(new SeverityCountsMapper());
    }

    private static ScanReportingSummary summaryOf(List<SpaceSummary> spaces, DashboardFacets facets) {
        return new ScanReportingSummary(
                "scan-123",
                Instant.parse("2025-01-15T10:00:00Z"),
                spaces == null ? 0 : spaces.size(),
                spaces,
                facets);
    }

    @Test
    void should_MapToDto_When_SummaryIsValid() {
        // Given
        SpaceSummary space = new SpaceSummary(
                "SPACE1", "IN_PROGRESS", 75.5, 100L, 50L,
                Instant.parse("2025-01-15T09:30:00Z"), "Space One",
                new SeverityCounts(10, 20, 5),
                Map.of("EMAIL", 7, "IBAN_CODE", 2));

        ScanReportingSummary summary = summaryOf(List.of(space), DashboardFacets.empty());

        // When
        ScanReportingSummaryDto result = mapper.toDto(summary);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.scanId()).isEqualTo("scan-123");
        assertThat(result.spacesCount()).isEqualTo(1);
        assertThat(result.spaces()).hasSize(1);

        SpaceSummaryDto spaceDto = result.spaces().get(0);
        assertThat(spaceDto.spaceKey()).isEqualTo("SPACE1");
        assertThat(spaceDto.status()).isEqualTo("IN_PROGRESS");
        assertThat(spaceDto.severityCounts().high()).isEqualTo(10);
        assertThat(spaceDto.severityCounts().total()).isEqualTo(35);
        assertThat(spaceDto.spaceName()).isEqualTo("Space One");
        assertThat(spaceDto.piiTypeCounts()).containsEntry("EMAIL", 7).containsEntry("IBAN_CODE", 2);
    }

    @Test
    void should_MapWithZeroSeverityCounts_When_CountsAreZero() {
        // Given
        SpaceSummary space = new SpaceSummary(
                "SPACE1", "COMPLETED", 100.0, 200L, 100L,
                Instant.parse("2025-01-15T10:00:00Z"), "Space One",
                SeverityCounts.zero(), Map.of());

        ScanReportingSummary summary = summaryOf(List.of(space), DashboardFacets.empty());

        // When
        ScanReportingSummaryDto result = mapper.toDto(summary);

        // Then
        SpaceSummaryDto spaceDto = result.spaces().get(0);
        assertThat(spaceDto.severityCounts().total()).isZero();
        assertThat(spaceDto.piiTypeCounts())
                .as("piiTypeCounts must be an empty map, never null")
                .isNotNull().isEmpty();
    }

    @Test
    void should_MapFacets_When_FacetsArePresent() {
        // Given
        DashboardFacets facets = new DashboardFacets(
                Map.of("EMAIL", new FacetCount(3, 12)),
                Map.of("HIGH", new FacetCount(2, 5)),
                Map.of("OK", new FacetCount(4, 17)));
        ScanReportingSummary summary = summaryOf(List.of(), facets);

        // When
        ScanReportingSummaryDto result = mapper.toDto(summary);

        // Then
        assertThat(result.facets().piiTypes().get("EMAIL").nbSpaces()).isEqualTo(3);
        assertThat(result.facets().piiTypes().get("EMAIL").totalOccurrences()).isEqualTo(12);
        assertThat(result.facets().severities().get("HIGH").nbSpaces()).isEqualTo(2);
        assertThat(result.facets().statuses().get("OK").totalOccurrences()).isEqualTo(17);
    }

    @Test
    void should_ReturnNull_When_SummaryIsNull() {
        assertThat(mapper.toDto(null)).isNull();
    }

    @Test
    void should_ReturnEmptySpacesList_When_SpacesIsNull() {
        ScanReportingSummary summary = summaryOf(null, DashboardFacets.empty());

        ScanReportingSummaryDto result = mapper.toDto(summary);

        assertThat(result).isNotNull();
        assertThat(result.spaces()).isEmpty();
    }

    @Test
    void should_ReturnEmptySpacesList_When_SpacesIsEmpty() {
        ScanReportingSummary summary = summaryOf(List.of(), DashboardFacets.empty());

        ScanReportingSummaryDto result = mapper.toDto(summary);

        assertThat(result.spaces()).isEmpty();
    }

    @Test
    void should_HandleNullSpace_When_SpaceIsNullInList() {
        ScanReportingSummary summary = summaryOf(Arrays.asList((SpaceSummary) null), DashboardFacets.empty());

        ScanReportingSummaryDto result = mapper.toDto(summary);

        assertThat(result.spaces()).hasSize(1);
        assertThat(result.spaces().get(0)).isNull();
    }

    @Test
    void should_DefaultEmptyFacets_When_FacetsAreNull() {
        ScanReportingSummary summary = summaryOf(List.of(), null);

        ScanReportingSummaryDto result = mapper.toDto(summary);

        assertThat(result.facets()).isNotNull();
        assertThat(result.facets().piiTypes()).isEmpty();
        assertThat(result.facets().severities()).isEmpty();
        assertThat(result.facets().statuses()).isEmpty();
    }
}
