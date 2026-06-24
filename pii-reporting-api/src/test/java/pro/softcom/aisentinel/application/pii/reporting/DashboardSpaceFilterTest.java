package pro.softcom.aisentinel.application.pii.reporting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.reporting.DashboardFacets;
import pro.softcom.aisentinel.domain.pii.reporting.FacetCount;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;
import pro.softcom.aisentinel.domain.pii.reporting.SpaceSummary;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DashboardSpaceFilter}: filtering semantics, search, sorting and contextual facets.
 */
@DisplayName("DashboardSpaceFilter")
class DashboardSpaceFilterTest {

    private static SpaceSummary space(String key, String name, String status,
                                      SeverityCounts severity, Map<String, Integer> piiTypes, Instant lastEvent) {
        return new SpaceSummary(key, status, 100.0, 0L, 0L, lastEvent, name, severity, piiTypes);
    }

    private static DashboardFilterCriteria criteria(List<String> piiTypes, List<String> severities,
                                                    List<String> statuses, String search, String sort, String order) {
        return new DashboardFilterCriteria(piiTypes, severities, statuses, search, sort, order);
    }

    private static List<String> keys(List<SpaceSummary> spaces) {
        return spaces.stream().map(SpaceSummary::spaceKey).toList();
    }

    @Nested
    @DisplayName("Filtering")
    class Filtering {

        @Test
        @DisplayName("Should_ReturnAll_When_NoCriteria")
        void Should_ReturnAll_When_NoCriteria() {
            List<SpaceSummary> all = List.of(
                    space("A", "Alpha", "COMPLETED", new SeverityCounts(1, 0, 0), Map.of("EMAIL", 1), null),
                    space("B", "Beta", "RUNNING", SeverityCounts.zero(), Map.of(), null));

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(all, DashboardFilterCriteria.none());

            assertThat(keys(result.spaces())).containsExactlyInAnyOrder("A", "B");
        }

        @Test
        @DisplayName("Should_MatchOnPositiveThreshold_When_PiiTypeSelected")
        void Should_MatchOnPositiveThreshold_When_PiiTypeSelected() {
            SpaceSummary withEmail = space("A", "Alpha", "COMPLETED",
                    new SeverityCounts(1, 0, 0), Map.of("EMAIL", 3), null);
            SpaceSummary zeroEmail = space("B", "Beta", "COMPLETED",
                    new SeverityCounts(1, 0, 0), Map.of("EMAIL", 0), null);
            SpaceSummary noEmail = space("C", "Gamma", "COMPLETED",
                    new SeverityCounts(1, 0, 0), Map.of("PHONE_NUMBER", 2), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(withEmail, zeroEmail, noEmail),
                    criteria(List.of("EMAIL"), List.of(), List.of(), null, null, null));

            assertThat(keys(result.spaces())).containsExactly("A");
        }

        @Test
        @DisplayName("Should_OrWithinAxis_When_MultiplePiiTypesSelected")
        void Should_OrWithinAxis_When_MultiplePiiTypesSelected() {
            SpaceSummary a = space("A", "Alpha", "COMPLETED", SeverityCounts.zero(), Map.of("EMAIL", 1), null);
            SpaceSummary b = space("B", "Beta", "COMPLETED", SeverityCounts.zero(), Map.of("PHONE_NUMBER", 1), null);
            SpaceSummary c = space("C", "Gamma", "COMPLETED", SeverityCounts.zero(), Map.of("IBAN_CODE", 1), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(a, b, c),
                    criteria(List.of("EMAIL", "PHONE_NUMBER"), List.of(), List.of(), null, null, null));

            assertThat(keys(result.spaces())).containsExactlyInAnyOrder("A", "B");
        }

        @Test
        @DisplayName("Should_AndAcrossAxes_When_PiiTypeAndSeveritySelected")
        void Should_AndAcrossAxes_When_PiiTypeAndSeveritySelected() {
            SpaceSummary match = space("A", "Alpha", "COMPLETED",
                    new SeverityCounts(2, 0, 0), Map.of("EMAIL", 1), null);
            SpaceSummary emailButLow = space("B", "Beta", "COMPLETED",
                    new SeverityCounts(0, 0, 5), Map.of("EMAIL", 1), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(match, emailButLow),
                    criteria(List.of("EMAIL"), List.of("HIGH"), List.of(), null, null, null));

            assertThat(keys(result.spaces())).containsExactly("A");
        }

        @Test
        @DisplayName("Should_MapSeverityBuckets_When_SeveritySelected")
        void Should_MapSeverityBuckets_When_SeveritySelected() {
            SpaceSummary high = space("H", "High", "COMPLETED", new SeverityCounts(3, 0, 0), Map.of(), null);
            SpaceSummary medium = space("M", "Med", "COMPLETED", new SeverityCounts(0, 4, 0), Map.of(), null);
            SpaceSummary low = space("L", "Low", "COMPLETED", new SeverityCounts(0, 0, 5), Map.of(), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(high, medium, low),
                    criteria(List.of(), List.of("MEDIUM"), List.of(), null, null, null));

            assertThat(keys(result.spaces())).containsExactly("M");
        }
    }

    @Nested
    @DisplayName("Status mapping")
    class StatusMapping {

        @Test
        @DisplayName("Should_MatchOkUiStatus_When_BackendIsCompleted")
        void Should_MatchOkUiStatus_When_BackendIsCompleted() {
            SpaceSummary completed = space("A", "Alpha", "COMPLETED", SeverityCounts.zero(), Map.of(), null);
            SpaceSummary running = space("B", "Beta", "RUNNING", SeverityCounts.zero(), Map.of(), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(completed, running),
                    criteria(List.of(), List.of(), List.of("OK"), null, null, null));

            assertThat(keys(result.spaces())).containsExactly("A");
        }

        @Test
        @DisplayName("Should_MatchNotStartedUiStatus_When_StatusIsNull")
        void Should_MatchNotStartedUiStatus_When_StatusIsNull() {
            SpaceSummary neverScanned = space("A", "Alpha", null, SeverityCounts.zero(), Map.of(), null);
            SpaceSummary running = space("B", "Beta", "RUNNING", SeverityCounts.zero(), Map.of(), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(neverScanned, running),
                    criteria(List.of(), List.of(), List.of("NOT_STARTED"), null, null, null));

            assertThat(keys(result.spaces())).containsExactly("A");
        }

        @Test
        @DisplayName("Should_PassThroughUiStatus_When_BackendIsFailed")
        void Should_PassThroughUiStatus_When_BackendIsFailed() {
            SpaceSummary failed = space("A", "Alpha", "FAILED", SeverityCounts.zero(), Map.of(), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(failed),
                    criteria(List.of(), List.of(), List.of("FAILED"), null, null, null));

            assertThat(keys(result.spaces())).containsExactly("A");
        }
    }

    @Nested
    @DisplayName("Search")
    class Search {

        @Test
        @DisplayName("Should_MatchNameCaseInsensitive_When_SearchProvided")
        void Should_MatchNameCaseInsensitive_When_SearchProvided() {
            SpaceSummary marketing = space("A", "Marketing Hub", "COMPLETED", SeverityCounts.zero(), Map.of(), null);
            SpaceSummary sales = space("B", "Sales", "COMPLETED", SeverityCounts.zero(), Map.of(), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(marketing, sales),
                    criteria(List.of(), List.of(), List.of(), "MARK", null, null));

            assertThat(keys(result.spaces())).containsExactly("A");
        }

        @Test
        @DisplayName("Should_FallbackToKey_When_NameIsNull")
        void Should_FallbackToKey_When_NameIsNull() {
            SpaceSummary noName = space("MKT", null, "COMPLETED", SeverityCounts.zero(), Map.of(), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(noName),
                    criteria(List.of(), List.of(), List.of(), "mkt", null, null));

            assertThat(keys(result.spaces())).containsExactly("MKT");
        }

        @Test
        @DisplayName("Should_ApplySearchAfterFilters_When_Combined")
        void Should_ApplySearchAfterFilters_When_Combined() {
            SpaceSummary emailMarketing = space("A", "Marketing", "COMPLETED",
                    SeverityCounts.zero(), Map.of("EMAIL", 1), null);
            SpaceSummary emailSales = space("B", "Sales", "COMPLETED",
                    SeverityCounts.zero(), Map.of("EMAIL", 1), null);
            SpaceSummary phoneMarketing = space("C", "Marketing Two", "COMPLETED",
                    SeverityCounts.zero(), Map.of("PHONE_NUMBER", 1), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(emailMarketing, emailSales, phoneMarketing),
                    criteria(List.of("EMAIL"), List.of(), List.of(), "marketing", null, null));

            assertThat(keys(result.spaces())).containsExactly("A");
        }
    }

    @Nested
    @DisplayName("Sorting")
    class Sorting {

        @Test
        @DisplayName("Should_SortByNameAscByDefault_When_SortIsName")
        void Should_SortByNameAscByDefault_When_SortIsName() {
            SpaceSummary b = space("B", "Beta", "COMPLETED", SeverityCounts.zero(), Map.of(), null);
            SpaceSummary a = space("A", "Alpha", "COMPLETED", SeverityCounts.zero(), Map.of(), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(b, a),
                    criteria(List.of(), List.of(), List.of(), null, "name", null));

            assertThat(keys(result.spaces())).containsExactly("A", "B");
        }

        @Test
        @DisplayName("Should_SortByTotalDetectionsDescByDefault_When_SortIsTotalDetections")
        void Should_SortByTotalDetectionsDescByDefault_When_SortIsTotalDetections() {
            SpaceSummary low = space("A", "Alpha", "COMPLETED", new SeverityCounts(0, 0, 1), Map.of(), null);
            SpaceSummary high = space("B", "Beta", "COMPLETED", new SeverityCounts(5, 5, 5), Map.of(), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(low, high),
                    criteria(List.of(), List.of(), List.of(), null, "totalDetections", null));

            assertThat(keys(result.spaces())).containsExactly("B", "A");
        }

        @Test
        @DisplayName("Should_HonorAscOrder_When_OrderIsAsc")
        void Should_HonorAscOrder_When_OrderIsAsc() {
            SpaceSummary low = space("A", "Alpha", "COMPLETED", new SeverityCounts(0, 0, 1), Map.of(), null);
            SpaceSummary high = space("B", "Beta", "COMPLETED", new SeverityCounts(5, 5, 5), Map.of(), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(high, low),
                    criteria(List.of(), List.of(), List.of(), null, "totalDetections", "asc"));

            assertThat(keys(result.spaces())).containsExactly("A", "B");
        }

        @Test
        @DisplayName("Should_RankBySeverityScore_When_SortIsSeverityScore")
        void Should_RankBySeverityScore_When_SortIsSeverityScore() {
            // 1 high beats any amount of medium/low
            SpaceSummary oneHigh = space("H", "High", "COMPLETED", new SeverityCounts(1, 0, 0), Map.of(), null);
            SpaceSummary manyMedium = space("M", "Med", "COMPLETED", new SeverityCounts(0, 999, 999), Map.of(), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(manyMedium, oneHigh),
                    criteria(List.of(), List.of(), List.of(), null, "severityScore", null));

            assertThat(keys(result.spaces())).containsExactly("H", "M");
        }

        @Test
        @DisplayName("Should_SortByLastScan_When_SortIsLastScan")
        void Should_SortByLastScan_When_SortIsLastScan() {
            SpaceSummary older = space("A", "Alpha", "COMPLETED", SeverityCounts.zero(), Map.of(),
                    Instant.parse("2024-01-01T00:00:00Z"));
            SpaceSummary newer = space("B", "Beta", "COMPLETED", SeverityCounts.zero(), Map.of(),
                    Instant.parse("2024-06-01T00:00:00Z"));

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(older, newer),
                    criteria(List.of(), List.of(), List.of(), null, "lastScan", null));

            assertThat(keys(result.spaces())).containsExactly("B", "A");
        }

        @Test
        @DisplayName("Should_HideZerosAndSort_When_SortIsPiiType")
        void Should_HideZerosAndSort_When_SortIsPiiType() {
            SpaceSummary many = space("A", "Alpha", "COMPLETED", SeverityCounts.zero(), Map.of("EMAIL", 9), null);
            SpaceSummary few = space("B", "Beta", "COMPLETED", SeverityCounts.zero(), Map.of("EMAIL", 2), null);
            SpaceSummary none = space("C", "Gamma", "COMPLETED", SeverityCounts.zero(), Map.of("PHONE_NUMBER", 5), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(few, none, many),
                    criteria(List.of(), List.of(), List.of(), null, "piiType:EMAIL", null));

            assertThat(keys(result.spaces())).containsExactly("A", "B");
        }

        @Test
        @DisplayName("Should_SortPiiTypeAsc_When_OrderIsAsc")
        void Should_SortPiiTypeAsc_When_OrderIsAsc() {
            SpaceSummary many = space("A", "Alpha", "COMPLETED", SeverityCounts.zero(), Map.of("EMAIL", 9), null);
            SpaceSummary few = space("B", "Beta", "COMPLETED", SeverityCounts.zero(), Map.of("EMAIL", 2), null);

            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(
                    List.of(many, few),
                    criteria(List.of(), List.of(), List.of(), null, "piiType:EMAIL", "asc"));

            assertThat(keys(result.spaces())).containsExactly("B", "A");
        }
    }

    @Nested
    @DisplayName("Facets")
    class Facets {

        @Test
        @DisplayName("Should_ComputePiiTypeFacets_When_NoSelection")
        void Should_ComputePiiTypeFacets_When_NoSelection() {
            SpaceSummary a = space("A", "Alpha", "COMPLETED", SeverityCounts.zero(), Map.of("EMAIL", 3), null);
            SpaceSummary b = space("B", "Beta", "COMPLETED", SeverityCounts.zero(), Map.of("EMAIL", 2), null);

            DashboardFacets facets = DashboardSpaceFilter.apply(
                    List.of(a, b), DashboardFilterCriteria.none()).facets();

            FacetCount email = facets.piiTypes().get("EMAIL");
            assertThat(email.nbSpaces()).isEqualTo(2);
            assertThat(email.totalOccurrences()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should_ComputeSeverityFacets_When_NoSelection")
        void Should_ComputeSeverityFacets_When_NoSelection() {
            SpaceSummary a = space("A", "Alpha", "COMPLETED", new SeverityCounts(2, 1, 0), Map.of(), null);
            SpaceSummary b = space("B", "Beta", "COMPLETED", new SeverityCounts(3, 0, 0), Map.of(), null);

            DashboardFacets facets = DashboardSpaceFilter.apply(
                    List.of(a, b), DashboardFilterCriteria.none()).facets();

            assertThat(facets.severities().get("HIGH").nbSpaces()).isEqualTo(2);
            assertThat(facets.severities().get("HIGH").totalOccurrences()).isEqualTo(5);
            assertThat(facets.severities().get("MEDIUM").nbSpaces()).isEqualTo(1);
            assertThat(facets.severities()).doesNotContainKey("LOW");
        }

        @Test
        @DisplayName("Should_ComputeStatusFacets_When_NoSelection")
        void Should_ComputeStatusFacets_When_NoSelection() {
            SpaceSummary completed = space("A", "Alpha", "COMPLETED", new SeverityCounts(1, 1, 0), Map.of(), null);
            SpaceSummary running = space("B", "Beta", "RUNNING", new SeverityCounts(0, 0, 3), Map.of(), null);

            DashboardFacets facets = DashboardSpaceFilter.apply(
                    List.of(completed, running), DashboardFilterCriteria.none()).facets();

            assertThat(facets.statuses().get("OK").nbSpaces()).isEqualTo(1);
            assertThat(facets.statuses().get("OK").totalOccurrences()).isEqualTo(2);
            assertThat(facets.statuses().get("RUNNING").totalOccurrences()).isEqualTo(3);
        }

        @Test
        @DisplayName("Should_ExcludeOwnAxisSelection_When_FacetsAreContextual")
        void Should_ExcludeOwnAxisSelection_When_FacetsAreContextual() {
            // Two spaces; severity HIGH selected. The severities facet must IGNORE its own selection
            // (so it still counts both HIGH and LOW), while the statuses facet must RESPECT the HIGH filter.
            SpaceSummary highOk = space("A", "Alpha", "COMPLETED", new SeverityCounts(2, 0, 0), Map.of(), null);
            SpaceSummary lowRunning = space("B", "Beta", "RUNNING", new SeverityCounts(0, 0, 4), Map.of(), null);

            DashboardFacets facets = DashboardSpaceFilter.apply(
                    List.of(highOk, lowRunning),
                    criteria(List.of(), List.of("HIGH"), List.of(), null, null, null)).facets();

            // severities facet excludes its own selection -> sees both spaces
            assertThat(facets.severities().get("HIGH").nbSpaces()).isEqualTo(1);
            assertThat(facets.severities().get("LOW").nbSpaces()).isEqualTo(1);
            // statuses facet respects the HIGH filter -> only the HIGH space remains
            assertThat(facets.statuses()).containsOnlyKeys("OK");
        }
    }
}
