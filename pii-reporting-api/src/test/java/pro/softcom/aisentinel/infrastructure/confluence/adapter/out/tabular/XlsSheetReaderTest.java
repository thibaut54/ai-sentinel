package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link XlsSheetReader}. All fixtures are generated programmatically with HSSF;
 * no binary files are committed.
 */
class XlsSheetReaderTest {

    private final XlsSheetReader reader = new XlsSheetReader();

    @Test
    @DisplayName("Should read header and two data rows verbatim including a numeric cell")
    void Should_ReadHeaderAndDataRows_When_WorkbookHasNumericCell() throws IOException {
        byte[] bytes = buildWorkbook(workbook -> {
            Sheet sheet = workbook.createSheet("People");
            writeStringRow(sheet, 0, "Name", "Age");
            Row dataA = sheet.createRow(1);
            dataA.createCell(0).setCellValue("Alice");
            dataA.createCell(1).setCellValue(42d);
            writeStringRow(sheet, 2, "Bob", "7");
        });

        List<SheetData> result = reader.read(bytes);

        assertThat(result).hasSize(1);
        SheetData sheet = result.get(0);
        assertThat(sheet.name()).isEqualTo("People");
        assertThat(sheet.rows()).hasSize(3);
        assertThat(sheet.rows().get(0)).containsExactly("Name", "Age");
        assertThat(sheet.rows().get(1)).containsExactly("Alice", "42");
        assertThat(sheet.rows().get(2)).containsExactly("Bob", "7");
    }

    @Test
    @DisplayName("Should return one SheetData per sheet When workbook has two sheets")
    void Should_ReturnOneSheetDataPerSheet_When_WorkbookHasTwoSheets() throws IOException {
        byte[] bytes = buildWorkbook(workbook -> {
            writeStringRow(workbook.createSheet("First"), 0, "a", "b");
            writeStringRow(workbook.createSheet("Second"), 0, "c", "d");
        });

        List<SheetData> result = reader.read(bytes);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("First");
        assertThat(result.get(0).rows().get(0)).containsExactly("a", "b");
        assertThat(result.get(1).name()).isEqualTo("Second");
        assertThat(result.get(1).rows().get(0)).containsExactly("c", "d");
    }

    @Test
    @DisplayName("Should gap-fill interior blank cell with empty string When a cell is missing")
    void Should_GapFillInteriorBlankCell_When_CellIsMissing() throws IOException {
        byte[] bytes = buildWorkbook(workbook -> {
            Sheet sheet = workbook.createSheet("Sparse");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("left");
            // column index 1 intentionally left out (interior blank)
            row.createCell(2).setCellValue("right");
        });

        List<SheetData> result = reader.read(bytes);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).rows()).hasSize(1);
        assertThat(result.get(0).rows().get(0)).containsExactly("left", "", "right");
    }

    @Test
    @DisplayName("Should return empty list When input bytes are empty")
    void Should_ReturnEmptyList_When_InputIsEmpty() throws IOException {
        assertThat(reader.read(new byte[0])).isEmpty();
        assertThat(reader.read(null)).isEmpty();
    }

    @Test
    @DisplayName("Should throw IOException When bytes are corrupted")
    void Should_ThrowIOException_When_BytesAreCorrupted() {
        byte[] corrupted = "this is not an xls workbook".getBytes();

        assertThatThrownBy(() -> reader.read(corrupted))
                .isInstanceOf(IOException.class);
    }

    /** Functional callback used to populate a fixture workbook. */
    @FunctionalInterface
    private interface WorkbookPopulator {
        void populate(HSSFWorkbook workbook);
    }

    /**
     * Builds an in-memory .xls workbook and returns its serialized bytes.
     *
     * @param populator callback that creates sheets/rows/cells
     * @return the BIFF8 workbook bytes
     */
    private byte[] buildWorkbook(WorkbookPopulator populator) throws IOException {
        try (HSSFWorkbook workbook = new HSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            populator.populate(workbook);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Writes a row of string cells starting at column 0.
     *
     * @param sheet  the target sheet
     * @param rowIdx the 0-based row index
     * @param values the string values, one per column
     */
    private void writeStringRow(Sheet sheet, int rowIdx, String... values) {
        Row row = sheet.createRow(rowIdx);
        for (int c = 0; c < values.length; c++) {
            row.createCell(c).setCellValue(values[c]);
        }
    }
}
