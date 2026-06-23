package pro.softcom.aisentinel.domain.confluence.extraction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping.Segment;
import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping.Span;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OffsetMapping")
class OffsetMappingTest {

    @Test
    @DisplayName("Should return the same range when mapping is identity")
    void Should_ReturnSameRange_When_Identity() {
        OffsetMapping identity = OffsetMapping.identity();

        assertThat(identity.isIdentity()).isTrue();
        assertThat(identity.remap(5, 12)).contains(new Span(5, 12));
    }

    @Test
    @DisplayName("Should remap a range fully contained in a single value segment")
    void Should_Remap_When_RangeInsideSingleSegment() {
        // Analysis: "Nom : Dupont" -> value "Dupont" at analysis offset 6, length 6
        // Context:  "Dupont"       -> same value at context offset 0
        OffsetMapping mapping = new OffsetMapping(List.of(new Segment(6, 6, 0)));

        // Detector flags "Dupont" at analysis [6,12) -> context [0,6)
        assertThat(mapping.remap(6, 12)).contains(new Span(0, 6));
        // Sub-range "pont" at analysis [8,12) -> context [2,6)
        assertThat(mapping.remap(8, 12)).contains(new Span(2, 6));
    }

    @Test
    @DisplayName("Should remap against the correct segment among several")
    void Should_Remap_When_MultipleSegments() {
        // analysis: "Nom : Dupont | Ville : Lyon"
        //            value "Dupont" at [6,12), value "Lyon" at [23,27)
        // context:  "Dupont Lyon"
        //            value "Dupont" at [0,6),  value "Lyon" at [7,11)
        OffsetMapping mapping = new OffsetMapping(List.of(
            new Segment(6, 6, 0),
            new Segment(23, 4, 7)
        ));

        assertThat(mapping.remap(6, 12)).contains(new Span(0, 6));
        assertThat(mapping.remap(23, 27)).contains(new Span(7, 11));
    }

    @Test
    @DisplayName("Should sort segments by analysis start so remap works on an unordered list")
    void Should_Remap_When_SegmentsProvidedUnordered() {
        OffsetMapping mapping = new OffsetMapping(List.of(
            new Segment(23, 4, 7),
            new Segment(6, 6, 0)
        ));

        assertThat(mapping.remap(6, 12)).contains(new Span(0, 6));
        assertThat(mapping.remap(23, 27)).contains(new Span(7, 11));
    }

    @Test
    @DisplayName("Should return empty when the range falls outside every value segment (label/separator)")
    void Should_ReturnEmpty_When_RangeOutsideSegments() {
        OffsetMapping mapping = new OffsetMapping(List.of(new Segment(6, 6, 0)));

        // Range over the synthetic label "Nom :" (analysis [0,5))
        assertThat(mapping.remap(0, 5)).isEmpty();
    }

    @Test
    @DisplayName("Should return empty when the range straddles a segment boundary")
    void Should_ReturnEmpty_When_RangeStraddlesBoundary() {
        OffsetMapping mapping = new OffsetMapping(List.of(
            new Segment(6, 6, 0),
            new Segment(23, 4, 7)
        ));

        // Starts inside "Dupont" but extends past its end into the separator/label
        assertThat(mapping.remap(10, 15)).isEmpty();
    }

    @Test
    @DisplayName("Should return empty for invalid or zero-length ranges on a tabular mapping")
    void Should_ReturnEmpty_When_RangeInvalidOrZeroLength() {
        OffsetMapping mapping = new OffsetMapping(List.of(new Segment(6, 6, 0)));

        assertThat(mapping.remap(-1, 3)).isEmpty();
        assertThat(mapping.remap(12, 8)).isEmpty();
        assertThat(mapping.remap(8, 8)).isEmpty();
    }

    @Test
    @DisplayName("Should treat a null segment list as identity")
    void Should_BeIdentity_When_NullSegments() {
        OffsetMapping mapping = new OffsetMapping(null);

        assertThat(mapping.isIdentity()).isTrue();
        assertThat(mapping.remap(2, 4)).contains(new Span(2, 4));
    }

    @Test
    @DisplayName("Should reject segments with non-positive length or negative offsets")
    void Should_Throw_When_SegmentInvalid() {
        assertThatThrownBy(() -> new Segment(0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Segment(-1, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Segment(0, 3, -2)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should preserve the verbatim value across both text spaces")
    void Should_PreserveValue_When_Remapping() {
        String analysis = "Nom : Dupont | Ville : Lyon";
        String context = "Dupont Lyon";
        OffsetMapping mapping = new OffsetMapping(List.of(
            new Segment(6, 6, 0),
            new Segment(23, 4, 7)
        ));

        Optional<Span> remapped = mapping.remap(6, 12);
        assertThat(remapped).isPresent();
        Span span = remapped.orElseThrow();
        assertThat(context.substring(span.start(), span.end()))
            .isEqualTo(analysis.substring(6, 12))
            .isEqualTo("Dupont");
    }
}
