package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular.TabularHeaderDetector.DetectedHeader;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TabularHeaderDetector")
class TabularHeaderDetectorTest {

    @Test
    @DisplayName("RG1: Should detect the first non-empty row as the header")
    void Should_DetectFirstRowAsHeader_When_HeaderPresent() {
        List<List<String>> rows = List.of(
            List.of("Nom", "Code postal", "N° AVS"),
            List.of("Dupont", "75019", "756.1234.5678.97"));

        Optional<DetectedHeader> header = TabularHeaderDetector.detectHeader(rows);

        assertThat(header).isPresent();
        assertThat(header.orElseThrow().headerRowIndex()).isZero();
        assertThat(header.orElseThrow().labels()).containsExactly("Nom", "Code postal", "N° AVS");
    }

    @Test
    @DisplayName("Should skip leading blank rows before the header")
    void Should_SkipLeadingBlankRows_When_HeaderFollows() {
        List<List<String>> rows = java.util.Arrays.asList(
            List.of("", "  "),
            List.of("Nom", "Ville"),
            List.of("Dupont", "Lyon"));

        Optional<DetectedHeader> header = TabularHeaderDetector.detectHeader(rows);

        assertThat(header).isPresent();
        assertThat(header.orElseThrow().headerRowIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("RG4: Should return empty when the sheet is empty")
    void Should_ReturnEmpty_When_SheetEmpty() {
        assertThat(TabularHeaderDetector.detectHeader(List.of())).isEmpty();
        assertThat(TabularHeaderDetector.detectHeader(null)).isEmpty();
    }

    @Test
    @DisplayName("RG4: Should return empty when the first non-empty row is purely numeric (data, not header)")
    void Should_ReturnEmpty_When_FirstRowAllNumeric() {
        List<List<String>> rows = List.of(
            List.of("756.1234.5678.97", "75019", "42"),
            List.of("756.9876.5432.10", "1003", "7"));

        assertThat(TabularHeaderDetector.detectHeader(rows)).isEmpty();
    }

    @Test
    @DisplayName("Should detect a header that mixes textual and numeric labels")
    void Should_DetectHeader_When_MixedLabels() {
        List<List<String>> rows = List.of(List.of("Nom", "2024"));

        Optional<DetectedHeader> header = TabularHeaderDetector.detectHeader(rows);

        assertThat(header).isPresent();
    }

    @Test
    @DisplayName("Should disambiguate duplicate labels and keep blanks empty")
    void Should_ResolveLabels_When_DuplicatesOrBlanks() {
        List<String> resolved = TabularHeaderDetector.resolveLabels(
            java.util.Arrays.asList("Nom", "Nom", " ", "Nom", null));

        assertThat(resolved).containsExactly("Nom", "Nom_2", "", "Nom_3", "");
    }
}
