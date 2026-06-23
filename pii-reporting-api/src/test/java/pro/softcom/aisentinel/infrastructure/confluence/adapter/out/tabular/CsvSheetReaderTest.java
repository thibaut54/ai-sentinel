package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CsvSheetReaderTest {

    private final CsvSheetReader reader = new CsvSheetReader();

    @Test
    @DisplayName("Should read header and two data rows verbatim with empty sheet name")
    void Should_ReadHeaderAndTwoDataRows_When_CommaDelimitedNominalInput() throws IOException {
        byte[] bytes = "name,age,city\nAlice ,30,Paris\nBob,25,Lyon".getBytes(StandardCharsets.UTF_8);

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets).hasSize(1);
        SheetData sheet = sheets.get(0);
        assertThat(sheet.name()).isEmpty();
        assertThat(sheet.rows()).containsExactly(
                List.of("name", "age", "city"),
                List.of("Alice ", "30", "Paris"),
                List.of("Bob", "25", "Lyon"));
    }

    @Test
    @DisplayName("Should return empty sheet list when input is empty")
    void Should_ReturnEmptyList_When_InputIsEmpty() throws IOException {
        assertThat(reader.read(new byte[0])).isEmpty();
        assertThat(reader.read(null)).isEmpty();
    }

    @Test
    @DisplayName("Should throw IOException when quoting is malformed")
    void Should_ThrowIOException_When_QuotingIsMalformed() {
        // An unterminated quote with embedded delimiter makes Commons CSV fail.
        byte[] bytes = "a,b\n\"unterminated,c\nd,e".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(bytes)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Should auto-detect semicolon delimiter")
    void Should_DetectSemicolon_When_SemicolonDelimited() throws IOException {
        byte[] bytes = "a;b;c\n1;2;3".getBytes(StandardCharsets.UTF_8);

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets).hasSize(1);
        assertThat(sheets.get(0).rows()).containsExactly(
                List.of("a", "b", "c"),
                List.of("1", "2", "3"));
    }

    @Test
    @DisplayName("Should keep delimiter inside a quoted field as part of the cell value")
    void Should_PreserveDelimiter_When_QuotedFieldContainsDelimiter() throws IOException {
        byte[] bytes = "name,note\nAlice,\"hello, world\"".getBytes(StandardCharsets.UTF_8);

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets.get(0).rows()).containsExactly(
                List.of("name", "note"),
                List.of("Alice", "hello, world"));
    }

    @Test
    @DisplayName("Should gap-fill an empty middle field as empty string")
    void Should_GapFillEmptyMiddleField_When_FieldIsBlank() throws IOException {
        byte[] bytes = "a,b,c\n1,,3".getBytes(StandardCharsets.UTF_8);

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets.get(0).rows().get(1)).containsExactly("1", "", "3");
    }

    @Test
    @DisplayName("Should keep trailing empty field as empty string")
    void Should_KeepTrailingEmptyField_When_RecordEndsWithDelimiter() throws IOException {
        byte[] bytes = "a,b,c\n1,2,".getBytes(StandardCharsets.UTF_8);

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets.get(0).rows().get(1)).containsExactly("1", "2", "");
    }

    @Test
    @DisplayName("Should strip a leading UTF-8 BOM from the first cell")
    void Should_StripBom_When_InputStartsWithUtf8Bom() throws IOException {
        byte[] body = "name,age\nAlice,30".getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets.get(0).rows().get(0)).containsExactly("name", "age");
    }

    @Test
    @DisplayName("Should preserve a delimiter and newline embedded in a quoted field")
    void Should_PreserveEmbeddedNewline_When_QuotedFieldSpansLines() throws IOException {
        byte[] bytes = "a,b\n\"line1\nline2\",x".getBytes(StandardCharsets.UTF_8);

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets.get(0).rows()).containsExactly(
                List.of("a", "b"),
                List.of("line1\nline2", "x"));
    }
}
