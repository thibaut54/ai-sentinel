package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SheetReader} implementation for legacy Excel {@code .xls} (BIFF8) workbooks,
 * backed by Apache POI's HSSF usermodel.
 *
 * <p>Each worksheet becomes one {@link SheetData}; cells are formatted to their displayed
 * string via {@link DataFormatter}, missing cells are gap-filled with the empty string so
 * that the list index equals the 0-based column index. Reading stops once {@link #MAX_CELLS}
 * accumulated cells is reached. Corrupted bytes are surfaced as {@link IOException}.
 */
public class XlsSheetReader implements SheetReader {

    /** Safety cap on the total number of cells accumulated across all sheets. */
    private static final int MAX_CELLS = 1_000_000;

    private static final Logger LOG = LoggerFactory.getLogger(XlsSheetReader.class);

    @Override
    public List<SheetData> read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return new ArrayList<>();
        }
        try (InputStream in = new ByteArrayInputStream(bytes);
             HSSFWorkbook workbook = new HSSFWorkbook(in)) {
            return readWorkbook(workbook);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse .xls workbook", e);
        }
    }

    /**
     * Reads every sheet of the workbook, stopping early when the cell cap is reached.
     *
     * @param workbook the open HSSF workbook
     * @return one {@link SheetData} per processed sheet
     */
    private List<SheetData> readWorkbook(HSSFWorkbook workbook) {
        List<SheetData> sheets = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        int cellBudget = MAX_CELLS;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (cellBudget <= 0) {
                LOG.warn("MAX_CELLS cap ({}) reached; stopping .xls read before sheet index {}", MAX_CELLS, i);
                break;
            }
            Sheet sheet = workbook.getSheetAt(i);
            List<List<String>> rows = new ArrayList<>();
            cellBudget = readRows(sheet, formatter, rows, cellBudget);
            sheets.add(new SheetData(workbook.getSheetName(i), rows));
        }
        return sheets;
    }

    /**
     * Reads the rows of a single sheet into {@code rows}, decrementing the shared cell budget.
     *
     * @param sheet       the worksheet to read
     * @param formatter   the cell-to-string formatter
     * @param rows        the accumulator the parsed rows are appended to
     * @param cellBudget  remaining cell budget before the cap
     * @return the remaining cell budget after this sheet
     */
    private int readRows(Sheet sheet, DataFormatter formatter, List<List<String>> rows, int cellBudget) {
        int budget = cellBudget;
        int lastRow = sheet.getLastRowNum();
        for (int r = 0; r <= lastRow; r++) {
            if (budget <= 0) {
                LOG.warn("MAX_CELLS cap ({}) reached; stopping .xls read mid-sheet", MAX_CELLS);
                break;
            }
            List<String> cells = readRow(sheet.getRow(r), formatter);
            budget -= cells.size();
            rows.add(cells);
        }
        return budget;
    }

    /**
     * Reads one row, gap-filling missing cells with the empty string up to the last column.
     *
     * @param row       the row, may be {@code null} for a wholly empty row
     * @param formatter the cell-to-string formatter
     * @return the verbatim cell values aligned by column index
     */
    private List<String> readRow(Row row, DataFormatter formatter) {
        List<String> cells = new ArrayList<>();
        if (row == null) {
            return cells;
        }
        int lastCol = row.getLastCellNum();
        for (int c = 0; c < lastCol; c++) {
            Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
            cells.add(formatter.formatCellValue(cell));
        }
        return cells;
    }
}
