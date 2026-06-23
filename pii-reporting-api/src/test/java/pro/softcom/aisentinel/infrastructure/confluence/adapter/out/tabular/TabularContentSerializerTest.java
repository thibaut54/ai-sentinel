package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.confluence.extraction.ExtractedContent;
import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping.Span;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("TabularContentSerializer")
class TabularContentSerializerTest {

    private final TabularContentSerializer serializer = new TabularContentSerializer();

    @Test
    @DisplayName("Should pair values with headers in the analysis text and keep raw values in the context text")
    void Should_BuildTrio_When_CsvWithHeader() {
        byte[] csv = "Nom,Code postal\nDupont,75019\nMartin,1003".getBytes(StandardCharsets.UTF_8);

        Optional<ExtractedContent> result = serializer.serialize("csv", csv);

        assertThat(result).isPresent();
        ExtractedContent content = result.orElseThrow();
        assertSoftly(soft -> {
            soft.assertThat(content.analysisText()).contains("Nom : Dupont", "Code postal : 75019");
            // Context must NOT carry the "header : value" decoration
            soft.assertThat(content.contextText()).doesNotContain(" : ");
            soft.assertThat(content.contextText()).isEqualTo("Dupont 75019\nMartin 1003");
            soft.assertThat(content.isIdentity()).isFalse();
        });
    }

    @Test
    @DisplayName("Should remap a detected value position from analysis space to the context value")
    void Should_KeepOffsetsCoherent_When_Remapping() {
        byte[] csv = "Nom,Code postal\nDupont,75019".getBytes(StandardCharsets.UTF_8);

        ExtractedContent content = serializer.serialize("csv", csv).orElseThrow();

        // Locate "Dupont" in the analysis text and remap it back to the context text
        int analysisStart = content.analysisText().indexOf("Dupont");
        Optional<Span> span = content.offsetMapping().remap(analysisStart, analysisStart + "Dupont".length());
        assertThat(span).isPresent();
        assertThat(content.contextText().substring(span.orElseThrow().start(), span.orElseThrow().end()))
            .isEqualTo("Dupont");
    }

    @Test
    @DisplayName("RG4: Should return empty when the table has no identifiable header")
    void Should_ReturnEmpty_When_NoHeader() {
        byte[] csv = "756.1234.5678.97,75019\n756.9876.5432.10,1003".getBytes(StandardCharsets.UTF_8);

        assertThat(serializer.serialize("csv", csv)).isEmpty();
    }

    @Test
    @DisplayName("Should return empty for an unsupported extension or empty bytes")
    void Should_ReturnEmpty_When_UnsupportedOrEmpty() {
        assertSoftly(soft -> {
            soft.assertThat(serializer.serialize("pdf", "x".getBytes(StandardCharsets.UTF_8))).isEmpty();
            soft.assertThat(serializer.serialize("csv", new byte[0])).isEmpty();
            soft.assertThat(serializer.serialize(null, "x".getBytes(StandardCharsets.UTF_8))).isEmpty();
        });
    }

    @Test
    @DisplayName("Should truncate on a row boundary and stay within the analysis cap on a large file")
    void Should_TruncateOnRowBoundary_When_CapExceeded() {
        StringBuilder csv = new StringBuilder("Nom,Note\n");
        String filler = "x".repeat(200);
        // ~220 chars per analysis line -> well over the 1M cap
        int rowCount = (TabularContentSerializer.MAX_ANALYSIS_TEXT_CHARS / 200) + 2_000;
        for (int i = 0; i < rowCount; i++) {
            csv.append("Dupont,").append(filler).append('\n');
        }

        ExtractedContent content = serializer.serialize("csv", csv.toString().getBytes(StandardCharsets.UTF_8))
            .orElseThrow();

        assertSoftly(soft -> {
            // Cap respected
            soft.assertThat(content.analysisText().length())
                .isLessThanOrEqualTo(TabularContentSerializer.MAX_ANALYSIS_TEXT_CHARS);
            // Truncation happened on a row boundary: same number of lines in both texts
            long analysisLines = content.analysisText().lines().count();
            long contextLines = content.contextText().lines().count();
            soft.assertThat(analysisLines).isEqualTo(contextLines);
            // Fewer emitted rows than the input -> truncation actually occurred
            soft.assertThat(analysisLines).isLessThan(rowCount);
        });
    }

    @Test
    @DisplayName("Should still emit a single over-cap first record verbatim (value never split) then stop")
    void Should_EmitFirstRecord_When_FirstRowAloneExceedsCap() {
        String huge = "v".repeat(TabularContentSerializer.MAX_ANALYSIS_TEXT_CHARS + 10);
        // One header + one data row whose value alone exceeds the cap, followed by a normal row
        String csv = "Nom,Note\nDupont," + huge + "\nMartin,ok";

        ExtractedContent content = serializer.serialize("csv", csv.getBytes(StandardCharsets.UTF_8))
            .orElseThrow();

        assertSoftly(soft -> {
            // The first (over-sized) record is emitted, not dropped...
            soft.assertThat(content.contextText().lines().count()).isEqualTo(1L);
            // ...verbatim (RG7): the huge value survives intact in the context text
            soft.assertThat(content.contextText()).contains(huge);
            // ...and reading stopped: the second row ("Martin") was not emitted
            soft.assertThat(content.contextText()).doesNotContain("Martin");
        });
    }
}
