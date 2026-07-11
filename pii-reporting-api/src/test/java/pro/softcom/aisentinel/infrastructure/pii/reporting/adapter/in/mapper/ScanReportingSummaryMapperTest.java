package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.reporting.service.DashboardFalsePositiveFilter;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScanReportingSummaryMapper}.
 *
 * <p>Verifies the transformation of domain objects into REST DTOs, including per-type counts and
 * facets (read from the domain) and the false-positive decrement applied to severity counters.
 */
@ExtendWith(MockitoExtension.class)
class ScanReportingSummaryMapperTest {

    @Mock
    private DashboardFalsePositiveFilter falsePositiveFilter;

    private ScanReportingSummaryMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ScanReportingSummaryMapper(new SeverityCountsMapper(), falsePositiveFilter);
        lenient().when(falsePositiveFilter.falsePositiveDelta(any(), any()))
                .thenReturn(SeverityCounts.zero());
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
                Map.of("EMAIL", 7, "IBAN_CODE", 2), "scan-123");

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
        assertThat(spaceDto.scanId()).isEqualTo("scan-123");
    }

    @Test
    void should_DecrementSeverityCounts_When_SpaceHasFalsePositives() {
        // Given: a space with a scan-time aggregate of 3 HIGH / 2 MEDIUM / 1 LOW.
        SpaceSummary space = new SpaceSummary(
                "SPACE1", "COMPLETED", 100.0, 10L, 0L,
                Instant.parse("2026-01-15T10:00:00Z"), "Space One",
                new SeverityCounts(3, 2, 1), Map.of(), "scan-fp");
        ScanReportingSummary summary = summaryOf(List.of(space), DashboardFacets.empty());

        // 1 HIGH and 3 LOW occurrences flagged false positive (more LOW than present -> floored at 0).
        when(falsePositiveFilter.falsePositiveDelta("scan-fp", "SPACE1"))
                .thenReturn(new SeverityCounts(1, 0, 3));

        // When
        ScanReportingSummaryDto result = mapper.toDto(summary);

        // Then: counters decremented and floored (2 HIGH / 2 MEDIUM / 0 LOW).
        SpaceSummaryDto spaceDto = result.spaces().get(0);
        assertThat(spaceDto.severityCounts().high()).isEqualTo(2);
        assertThat(spaceDto.severityCounts().medium()).isEqualTo(2);
        assertThat(spaceDto.severityCounts().low()).isZero();
    }

    @Test
    void should_FloorCountsToZero_When_DeltaExceedsBase() {
        // Given: a zero base aggregate but the space still reports false-positive occurrences.
        SpaceSummary space = new SpaceSummary(
                "SPACE1", "COMPLETED", 100.0, 10L, 0L,
                Instant.parse("2026-01-15T10:00:00Z"), "Space One",
                SeverityCounts.zero(), Map.of(), "scan-null-base");
        ScanReportingSummary summary = summaryOf(List.of(space), DashboardFacets.empty());

        when(falsePositiveFilter.falsePositiveDelta("scan-null-base", "SPACE1"))
                .thenReturn(new SeverityCounts(0, 0, 2));

        // When
        ScanReportingSummaryDto result = mapper.toDto(summary);

        // Then: the decrement is floored, yielding all-zero counts.
        assertThat(result.spaces().get(0).severityCounts().total()).isZero();
    }

    @Test
    void should_MapWithZeroSeverityCounts_When_CountsAreZero() {
        // Given
        SpaceSummary space = new SpaceSummary(
                "SPACE1", "COMPLETED", 100.0, 200L, 100L,
                Instant.parse("2025-01-15T10:00:00Z"), "Space One",
                SeverityCounts.zero(), Map.of(), "scan-123");

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
        assertThat(result.facets().piiTypes().get("EMAIL").spaceCount()).isEqualTo(3);
        assertThat(result.facets().piiTypes().get("EMAIL").totalOccurrences()).isEqualTo(12);
        assertThat(result.facets().severities().get("HIGH").spaceCount()).isEqualTo(2);
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
