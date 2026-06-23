package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OdsSheetReaderTest {

    private static final String NS =
            "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
                    + "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" "
                    + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\"";

    private final OdsSheetReader reader = new OdsSheetReader();

    @Test
    @DisplayName("Should return header and data rows with verbatim values when nominal ODS")
    void Should_ReturnHeaderAndDataRows_When_NominalOds() throws IOException {
        String body = table("Sheet1",
                row(cell("Name"), cell("Age"), cell("City"))
                        + row(cell(" Alice "), cell("30"), cell("Zurich"))
                        + row(cell("Bob"), cell("25"), cell("Bern")));

        List<SheetData> sheets = reader.read(ods(content(body)));

        assertThat(sheets).hasSize(1);
        SheetData sheet = sheets.get(0);
        assertThat(sheet.name()).isEqualTo("Sheet1");
        assertThat(sheet.rows()).hasSize(3);
        assertThat(sheet.rows().get(0)).containsExactly("Name", "Age", "City");
        assertThat(sheet.rows().get(1)).containsExactly(" Alice ", "30", "Zurich");
        assertThat(sheet.rows().get(2)).containsExactly("Bob", "25", "Bern");
    }

    @Test
    @DisplayName("Should gap-fill an interior blank when using number-columns-repeated")
    void Should_GapFillInteriorBlank_When_ColumnsRepeated() throws IOException {
        String repeatedBlank = "<table:table-cell table:number-columns-repeated=\"2\"/>";
        String body = table("Data",
                row(cell("A") + repeatedBlank + cell("D")));

        List<SheetData> sheets = reader.read(ods(content(body)));

        assertThat(sheets).hasSize(1);
        assertThat(sheets.get(0).rows()).hasSize(1);
        assertThat(sheets.get(0).rows().get(0)).containsExactly("A", "", "", "D");
    }

    @Test
    @DisplayName("Should return two sheets when content has multiple tables")
    void Should_ReturnTwoSheets_When_MultipleTables() throws IOException {
        String body = table("First", row(cell("x")))
                + table("Second", row(cell("y1")) + row(cell("y2")));

        List<SheetData> sheets = reader.read(ods(content(body)));

        assertThat(sheets).hasSize(2);
        assertThat(sheets.get(0).name()).isEqualTo("First");
        assertThat(sheets.get(0).rows()).hasSize(1);
        assertThat(sheets.get(0).rows().get(0)).containsExactly("x");
        assertThat(sheets.get(1).name()).isEqualTo("Second");
        assertThat(sheets.get(1).rows()).hasSize(2);
        assertThat(sheets.get(1).rows().get(1)).containsExactly("y2");
    }

    @Test
    @DisplayName("Should return empty list when input is empty")
    void Should_ReturnEmptyList_When_InputEmpty() throws IOException {
        assertThat(reader.read(new byte[0])).isEmpty();
        assertThat(reader.read(null)).isEmpty();
    }

    @Test
    @DisplayName("Should return empty sheet when table has no rows")
    void Should_ReturnEmptySheet_When_TableHasNoRows() throws IOException {
        List<SheetData> sheets = reader.read(ods(content(table("Empty", ""))));

        assertThat(sheets).hasSize(1);
        assertThat(sheets.get(0).name()).isEqualTo("Empty");
        assertThat(sheets.get(0).rows()).isEmpty();
    }

    @Test
    @DisplayName("Should throw IOException when bytes are not a zip")
    void Should_ThrowIoException_When_BytesNotZip() {
        byte[] garbage = "not a zip".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> reader.read(garbage)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Should throw IOException when zip has no content.xml")
    void Should_ThrowIoException_When_NoContentXml() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("other.xml"));
            zip.write("<x/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThatThrownBy(() -> reader.read(out.toByteArray())).isInstanceOf(IOException.class);
    }

    // --- ODS fixture builders -------------------------------------------------

    /** Wraps a content.xml body string into a full ODS byte[] archive. */
    private static byte[] ods(String contentXml) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeMimetype(zip);
            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(contentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    /** Writes the STORED, uncompressed mimetype entry mandated by the ODF package format. */
    private static void writeMimetype(ZipOutputStream zip) throws IOException {
        byte[] mime = "application/vnd.oasis.opendocument.spreadsheet"
                .getBytes(StandardCharsets.UTF_8);
        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(mime.length);
        CRC32 crc = new CRC32();
        crc.update(mime);
        entry.setCrc(crc.getValue());
        zip.putNextEntry(entry);
        zip.write(mime);
        zip.closeEntry();
    }

    private static String content(String tables) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<office:document-content " + NS + ">"
                + "<office:body><office:spreadsheet>"
                + tables
                + "</office:spreadsheet></office:body></office:document-content>";
    }

    private static String table(String name, String rows) {
        return "<table:table table:name=\"" + name + "\">" + rows + "</table:table>";
    }

    private static String row(String... cells) {
        return "<table:table-row>" + String.join("", cells) + "</table:table-row>";
    }

    private static String cell(String text) {
        return "<table:table-cell><text:p>" + text + "</text:p></table:table-cell>";
    }
}
