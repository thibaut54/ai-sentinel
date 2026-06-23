package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * Streaming reader for OOXML spreadsheets (.xlsx) based on Apache POI's SAX
 * event API (no full DOM). Each worksheet is parsed lazily and converted into a
 * {@link SheetData} whose rows preserve verbatim displayed cell values and
 * gap-fill missing cells so that the list index equals the 0-based column index.
 *
 * <p>This reader never loads a {@code XSSFWorkbook}; it relies on
 * {@link XSSFReader} together with a custom {@link SheetContentsHandler}.</p>
 */
public final class XlsxSheetReader implements SheetReader {

    private static final Logger LOG = LoggerFactory.getLogger(XlsxSheetReader.class);

    /** Safety cap on the total number of cells accumulated across all sheets. */
    private static final int MAX_CELLS = 1_000_000;

    @Override
    public List<SheetData> read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return new ArrayList<>();
        }
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(bytes))) {
            return readSheets(pkg);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to read xlsx workbook", e);
        }
    }

    /**
     * Iterates over every worksheet and accumulates its data until the global
     * cell cap is reached.
     *
     * @param pkg the opened OOXML package
     * @return one {@link SheetData} per parsed worksheet
     * @throws Exception when POI fails to expose or parse a worksheet
     */
    private List<SheetData> readSheets(OPCPackage pkg) throws Exception {
        ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
        XSSFReader reader = new XSSFReader(pkg);
        StylesTable styles = reader.getStylesTable();
        XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();

        List<SheetData> result = new ArrayList<>();
        CellBudget budget = new CellBudget();
        while (sheets.hasNext()) {
            if (budget.isExhausted()) {
                LOG.warn("Cell cap of {} reached; skipping remaining xlsx sheets", MAX_CELLS);
                break;
            }
            // next() advances the iterator and sets the current sheet ref; only then is
            // getSheetName() valid, so the stream MUST be obtained before reading the name.
            try (InputStream sheetStream = sheets.next()) {
                String name = sheets.getSheetName();
                result.add(parseSheet(sheetStream, name, styles, strings, budget));
            }
        }
        return result;
    }

    /**
     * Parses a single worksheet stream into a {@link SheetData}.
     *
     * @param sheetStream the worksheet XML stream
     * @param name        the worksheet name
     * @param styles      the workbook styles table
     * @param strings     the shared strings table
     * @param budget      the shared cell budget across all sheets
     * @return the parsed sheet data
     * @throws Exception when the worksheet XML cannot be parsed
     */
    private SheetData parseSheet(InputStream sheetStream, String name, StylesTable styles,
                                 ReadOnlySharedStringsTable strings, CellBudget budget) throws Exception {
        RowCollector collector = new RowCollector(budget);
        XMLReader xmlReader = XMLHelper.newXMLReader();
        xmlReader.setContentHandler(new XSSFSheetXMLHandler(
                styles, null, strings, collector, new DataFormatter(), false));
        xmlReader.parse(new InputSource(sheetStream));
        return new SheetData(name, collector.rows());
    }

    /** Mutable counter enforcing the global {@link #MAX_CELLS} cap. */
    private static final class CellBudget {
        private int count;

        boolean isExhausted() {
            return count >= MAX_CELLS;
        }

        void increment() {
            count++;
        }
    }

    /**
     * SAX handler collecting rows for one worksheet. Cell references are decoded
     * into 0-based column indices so interior blank cells are gap-filled with "".
     */
    private static final class RowCollector implements SheetContentsHandler {

        private final List<List<String>> rows = new ArrayList<>();
        private final CellBudget budget;
        private List<String> currentRow;

        RowCollector(CellBudget budget) {
            this.budget = budget;
        }

        List<List<String>> rows() {
            return rows;
        }

        @Override
        public void startRow(int rowNum) {
            currentRow = new ArrayList<>();
        }

        @Override
        public void endRow(int rowNum) {
            if (currentRow != null && !budget.isExhausted()) {
                rows.add(currentRow);
            }
            currentRow = null;
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (currentRow == null || budget.isExhausted()) {
                return;
            }
            int column = columnIndex(cellReference, currentRow.size());
            while (currentRow.size() < column) {
                currentRow.add("");
            }
            currentRow.add(formattedValue == null ? "" : formattedValue);
            budget.increment();
        }

        /**
         * Resolves the 0-based column index for a cell reference, falling back to
         * the next sequential column when the reference is absent.
         *
         * @param cellReference the A1-style reference (e.g. "C5"), may be null
         * @param fallback      the next column index to use when no reference
         * @return the 0-based column index
         */
        private int columnIndex(String cellReference, int fallback) {
            if (cellReference == null) {
                return fallback;
            }
            return new CellReference(cellReference).getCol();
        }
    }
}
