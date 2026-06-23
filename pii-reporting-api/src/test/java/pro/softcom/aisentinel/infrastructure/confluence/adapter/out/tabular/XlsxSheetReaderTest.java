package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link XlsxSheetReader}. All fixtures are produced
 * programmatically with {@link XSSFWorkbook} (write only) and parsed back with
 * the streaming reader under test.
 */
class XlsxSheetReaderTest {

    private final XlsxSheetReader reader = new XlsxSheetReader();

    @Test
    @DisplayName("Should return header and data rows with verbatim values when sheet is nominal")
    void Should_ReturnVerbatimRows_When_SheetIsNominal() throws IOException {
        byte[] bytes = workbook(wb -> {
            Sheet sheet = wb.createSheet("People");
            writeRow(sheet, 0, "Name", "Age", "City");
            writeRow(sheet, 1, "Alice", null, "Geneva");
            Row numeric = sheet.createRow(2);
            numeric.createCell(0).setCellValue("Bob");
            numeric.createCell(1).setCellValue(42);
            numeric.createCell(2).setCellValue("Lausanne");
        });

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets).hasSize(1);
        SheetData data = sheets.get(0);
        assertThat(data.name()).isEqualTo("People");
        assertThat(data.rows()).hasSize(3);
        assertThat(data.rows().get(0)).containsExactly("Name", "Age", "City");
        assertThat(data.rows().get(1)).containsExactly("Alice", "", "Geneva");
        assertThat(data.rows().get(2)).containsExactly("Bob", "42", "Lausanne");
    }

    @Test
    @DisplayName("Should return both sheets with names and contents when workbook has two sheets")
    void Should_ReturnBothSheets_When_WorkbookHasTwoSheets() throws IOException {
        byte[] bytes = workbook(wb -> {
            writeRow(wb.createSheet("First"), 0, "a", "b");
            writeRow(wb.createSheet("Second"), 0, "c", "d");
        });

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets).hasSize(2);
        assertThat(sheets.get(0).name()).isEqualTo("First");
        assertThat(sheets.get(0).rows().get(0)).containsExactly("a", "b");
        assertThat(sheets.get(1).name()).isEqualTo("Second");
        assertThat(sheets.get(1).rows().get(0)).containsExactly("c", "d");
    }

    @Test
    @DisplayName("Should gap-fill interior blank cell with empty string when cell is skipped")
    void Should_GapFillInteriorBlankCell_When_CellIsSkipped() throws IOException {
        byte[] bytes = workbook(wb -> {
            Sheet sheet = wb.createSheet("Sparse");
            Row row = sheet.createRow(0);
            // Column index 1 left untouched so the worksheet XML skips it entirely.
            row.createCell(0).setCellValue("left");
            row.createCell(2).setCellValue("right");
        });

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets).hasSize(1);
        assertThat(sheets.get(0).rows()).hasSize(1);
        assertThat(sheets.get(0).rows().get(0)).containsExactly("left", "", "right");
    }

    @Test
    @DisplayName("Should return empty list when input bytes are empty")
    void Should_ReturnEmptyList_When_InputIsEmpty() throws IOException {
        assertThat(reader.read(new byte[0])).isEmpty();
        assertThat(reader.read(null)).isEmpty();
    }

    @Test
    @DisplayName("Should return single empty sheet when workbook has an empty sheet")
    void Should_ReturnEmptySheet_When_WorkbookSheetIsEmpty() throws IOException {
        byte[] bytes = workbook(wb -> wb.createSheet("Empty"));

        List<SheetData> sheets = reader.read(bytes);

        assertThat(sheets).hasSize(1);
        assertThat(sheets.get(0).name()).isEqualTo("Empty");
        assertThat(sheets.get(0).rows()).isEmpty();
    }

    @Test
    @DisplayName("Should throw IOException when bytes are not a valid OOXML package")
    void Should_ThrowIOException_When_BytesAreCorrupted() {
        byte[] corrupted = "this is definitely not a xlsx zip".getBytes();

        assertThatThrownBy(() -> reader.read(corrupted)).isInstanceOf(IOException.class);
    }

    /**
     * Builds an in-memory .xlsx and returns its bytes.
     *
     * @param populator callback that writes content into the workbook
     * @return the serialized workbook bytes
     * @throws IOException when serialization fails
     */
    private byte[] workbook(WorkbookPopulator populator) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            populator.populate(wb);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Writes a row of string cells starting at column 0.
     *
     * @param sheet  the target sheet
     * @param rowNum the 0-based row index
     * @param values the cell values; a null value leaves that column untouched
     */
    private void writeRow(Sheet sheet, int rowNum, String... values) {
        Row row = sheet.createRow(rowNum);
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                row.createCell(i).setCellValue(values[i]);
            }
        }
    }

    /** Functional callback to populate a workbook fixture. */
    @FunctionalInterface
    private interface WorkbookPopulator {
        void populate(XSSFWorkbook workbook);
    }
}
