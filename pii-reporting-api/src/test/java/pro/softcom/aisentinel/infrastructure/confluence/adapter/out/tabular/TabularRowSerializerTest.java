package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping.Segment;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("TabularRowSerializer")
class TabularRowSerializerTest {

    @Test
    @DisplayName("Should pair each value with its column header on the analysis side and keep raw values for context")
    void Should_BuildBothTexts_When_NominalRow() {
        SerializedRow row = TabularRowSerializer.serialize(
            null, List.of("Nom", "Code postal"), List.of("Dupont", "75019"));

        assertSoftly(soft -> {
            soft.assertThat(row.analysisFragment()).isEqualTo("Nom : Dupont | Code postal : 75019");
            soft.assertThat(row.contextFragment()).isEqualTo("Dupont 75019");
            soft.assertThat(row.valueSegments()).hasSize(2);
        });
        assertSegmentsConsistent(row);
    }

    @Test
    @DisplayName("RG2: Should skip empty cells entirely (no label, no value, no segment)")
    void Should_SkipEmptyCells_When_RG2() {
        SerializedRow row = TabularRowSerializer.serialize(
            null, List.of("Nom", "Age", "Ville"), Arrays.asList("Dupont", "", "Lyon"));

        assertThat(row.analysisFragment()).isEqualTo("Nom : Dupont | Ville : Lyon");
        assertThat(row.contextFragment()).isEqualTo("Dupont Lyon");
        assertThat(row.valueSegments()).hasSize(2);
        assertSegmentsConsistent(row);
    }

    @Test
    @DisplayName("RG3: Should render a column without a header as 'Colonne N'")
    void Should_UseColumnFallback_When_HeaderBlank() {
        SerializedRow row = TabularRowSerializer.serialize(
            null, Arrays.asList("Nom", ""), List.of("Dupont", "secret"));

        assertThat(row.analysisFragment()).isEqualTo("Nom : Dupont | Colonne 2 : secret");
        assertSegmentsConsistent(row);
    }

    @Test
    @DisplayName("RG6: Should prefix the sheet name on the analysis side only, never in the context")
    void Should_PrefixSheetName_When_RG6() {
        SerializedRow row = TabularRowSerializer.serialize(
            "Clients", List.of("Nom"), List.of("Dupont"));

        assertThat(row.analysisFragment()).isEqualTo("Clients | Nom : Dupont");
        assertThat(row.contextFragment()).isEqualTo("Dupont");
        assertSegmentsConsistent(row);
    }

    @Test
    @DisplayName("RG7: Should keep the value verbatim, including a pipe separator inside it")
    void Should_KeepValueVerbatim_When_ValueContainsPipe() {
        SerializedRow row = TabularRowSerializer.serialize(
            null, List.of("Note"), List.of("a | b | c"));

        assertThat(row.contextFragment()).isEqualTo("a | b | c");
        // The segment points exactly to the verbatim value in both spaces
        Segment segment = row.valueSegments().getFirst();
        assertThat(row.contextFragment().substring(segment.contextStart(),
            segment.contextStart() + segment.length())).isEqualTo("a | b | c");
        assertSegmentsConsistent(row);
    }

    @Test
    @DisplayName("Should return an empty row when every cell is blank")
    void Should_ReturnEmptyRow_When_AllCellsBlank() {
        SerializedRow row = TabularRowSerializer.serialize(
            "Sheet", List.of("A", "B"), Arrays.asList("", "   "));

        assertThat(row.isEmpty()).isTrue();
        assertThat(row.analysisFragment()).isEmpty();
        assertThat(row.contextFragment()).isEmpty();
    }

    @Test
    @DisplayName("Should anchor each segment on the exact same characters in both text spaces")
    void Should_KeepSegmentsConsistent_When_MultipleValues() {
        SerializedRow row = TabularRowSerializer.serialize(
            "Feuille1", List.of("Nom", "Code postal", "AVS"),
            List.of("Dupont", "75019", "756.1234.5678.97"));

        assertSegmentsConsistent(row);
    }

    /**
     * Verifies the core invariant: every value segment points to identical characters in the
     * analysis fragment and in the context fragment (the value is verbatim in both — RG7).
     */
    private static void assertSegmentsConsistent(SerializedRow row) {
        for (Segment segment : row.valueSegments()) {
            String fromAnalysis = row.analysisFragment()
                .substring(segment.analysisStart(), segment.analysisStart() + segment.length());
            String fromContext = row.contextFragment()
                .substring(segment.contextStart(), segment.contextStart() + segment.length());
            assertThat(fromAnalysis).isEqualTo(fromContext);
        }
    }
}
