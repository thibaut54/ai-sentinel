package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * {@link SheetReader} for OpenDocument Spreadsheet (.ods) files.
 *
 * <p>An ODS file is a ZIP archive. This reader locates the {@code content.xml} entry and
 * SAX-parses it without any ODF library. It honours the OpenDocument repetition attributes
 * ({@code table:number-columns-repeated} / {@code table:number-rows-repeated}) while capping
 * trailing repeats to avoid huge expansions.
 */
public final class OdsSheetReader implements SheetReader {

    /** Safety cap on the total number of accumulated cells across all sheets. */
    private static final int MAX_CELLS = 1_000_000;

    private static final Logger LOG = LoggerFactory.getLogger(OdsSheetReader.class);

    private static final String CONTENT_ENTRY = "content.xml";

    @Override
    public List<SheetData> read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return new ArrayList<>();
        }
        byte[] content = extractContentXml(bytes);
        if (content == null) {
            throw new IOException("No content.xml entry found: not a valid ODS file");
        }
        return parseContent(content);
    }

    /**
     * Extracts the raw bytes of the {@code content.xml} entry from the ODS ZIP archive.
     *
     * @return the entry bytes, or {@code null} when the entry is absent
     * @throws IOException when the bytes are not a readable ZIP archive
     */
    private byte[] extractContentXml(byte[] bytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            boolean sawEntry = false;
            while ((entry = zip.getNextEntry()) != null) {
                sawEntry = true;
                if (CONTENT_ENTRY.equals(entry.getName())) {
                    return zip.readAllBytes();
                }
            }
            if (!sawEntry) {
                throw new IOException("Input is not a ZIP archive");
            }
            return null;
        } catch (RuntimeException e) {
            throw new IOException("Failed to read ODS archive", e);
        }
    }

    /**
     * SAX-parses the {@code content.xml} bytes into the list of sheets.
     *
     * @throws IOException when the XML is malformed or the parser cannot be configured
     */
    private List<SheetData> parseContent(byte[] content) throws IOException {
        try {
            SAXParser parser = newSecureParser();
            OdsHandler handler = new OdsHandler();
            try (InputStream in = new ByteArrayInputStream(content)) {
                parser.parse(in, handler);
            }
            return handler.sheets();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse ODS content.xml", e);
        }
    }

    /**
     * Builds a SAX parser with external DTD loading disabled.
     */
    private SAXParser newSecureParser() throws ParserConfigurationException, SAXException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newSAXParser();
    }

    /**
     * SAX handler that materialises the OpenDocument table structure into {@link SheetData}.
     *
     * <p>Repetition attributes are expanded eagerly except for a trailing empty cell/row, whose
     * repeat count is ignored to avoid millions of meaningless padding entries.
     */
    private static final class OdsHandler extends DefaultHandler {

        private static final int MAX_TRAILING_REPEAT = 1;
        /** Upper bound for a single repeat count, guarding against pathological expansions. */
        private static final int MAX_REPEAT = 4096;

        private final List<SheetData> sheets = new ArrayList<>();

        private String currentSheetName;
        private List<List<String>> currentRows;
        private List<String> currentRow;
        private int rowRepeat;
        private int cellRepeat;
        /** Empty repeated cells held back until a later non-empty cell (interior padding) flushes
         *  them; discarded at end of row when they are only trailing padding. */
        private int pendingBlanks;
        private final StringBuilder cellText = new StringBuilder();
        private boolean inText;
        private int totalCells;
        private boolean capped;

        List<SheetData> sheets() {
            return sheets;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            if (capped) {
                return;
            }
            switch (local(qName)) {
                case "table" -> startTable(attrs);
                case "table-row" -> startRow(attrs);
                case "table-cell", "covered-table-cell" -> startCell(attrs);
                case "p" -> startParagraph();
                default -> { /* ignore other elements */ }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (capped) {
                return;
            }
            switch (local(qName)) {
                case "table" -> endTable();
                case "table-row" -> endRow();
                case "table-cell", "covered-table-cell" -> endCell();
                case "p" -> inText = false;
                default -> { /* ignore other elements */ }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inText && !capped) {
                cellText.append(ch, start, length);
            }
        }

        private void startTable(Attributes attrs) {
            currentSheetName = orEmpty(value(attrs, "name"));
            currentRows = new ArrayList<>();
        }

        private void endTable() {
            if (currentRows != null) {
                sheets.add(new SheetData(orEmpty(currentSheetName), currentRows));
            }
            currentRows = null;
            currentSheetName = null;
        }

        private void startRow(Attributes attrs) {
            currentRow = new ArrayList<>();
            pendingBlanks = 0;
            rowRepeat = repeat(attrs, "number-rows-repeated");
        }

        private void endRow() {
            if (currentRows == null) {
                return;
            }
            // Pending blank cells left at this point are trailing padding: discard them.
            pendingBlanks = 0;
            int effective = trailingSafeRepeat(rowRepeat, currentRow.isEmpty());
            for (int i = 0; i < effective && !capped; i++) {
                addRow(new ArrayList<>(currentRow));
            }
            currentRow = null;
        }

        private void startCell(Attributes attrs) {
            cellText.setLength(0);
            cellRepeat = repeat(attrs, "number-columns-repeated");
        }

        private void endCell() {
            if (currentRow == null) {
                return;
            }
            String value = cellText.toString();
            if (value.isEmpty()) {
                // Defer empty cells: they only materialise if a later non-empty cell follows.
                pendingBlanks += cellRepeat;
                return;
            }
            flushPendingBlanks();
            for (int i = 0; i < cellRepeat; i++) {
                currentRow.add(value);
            }
        }

        private void flushPendingBlanks() {
            for (int i = 0; i < pendingBlanks; i++) {
                currentRow.add("");
            }
            pendingBlanks = 0;
        }

        private void startParagraph() {
            if (cellText.length() > 0) {
                cellText.append('\n');
            }
            inText = true;
        }

        /**
         * Adds a fully built row to the current sheet, enforcing {@link #MAX_CELLS}.
         */
        private void addRow(List<String> row) {
            totalCells += row.size();
            if (totalCells > MAX_CELLS) {
                LOG.warn("ODS cell cap of {} reached; stopping further reading", MAX_CELLS);
                capped = true;
                return;
            }
            currentRows.add(row);
        }

        /**
         * Caps a repeat count to {@link #MAX_TRAILING_REPEAT} when the repeated element is empty
         * (typically a trailing padding cell/row in OpenDocument).
         */
        private int trailingSafeRepeat(int repeat, boolean empty) {
            return empty ? Math.min(repeat, MAX_TRAILING_REPEAT) : repeat;
        }

        private static int repeat(Attributes attrs, String name) {
            String raw = value(attrs, name);
            if (raw == null) {
                return 1;
            }
            try {
                return Math.clamp(Integer.parseInt(raw.trim()), 1, MAX_REPEAT);
            } catch (NumberFormatException e) {
                return 1;
            }
        }

        /**
         * Returns the value of a {@code table:}-style attribute by its local name, regardless of
         * how the namespace prefix is resolved.
         */
        private static String value(Attributes attrs, String localName) {
            for (int i = 0; i < attrs.getLength(); i++) {
                if (localName.equals(local(attrs.getQName(i)))
                        || localName.equals(attrs.getLocalName(i))) {
                    return attrs.getValue(i);
                }
            }
            return null;
        }

        private static String local(String qName) {
            if (qName == null) {
                return "";
            }
            int idx = qName.indexOf(':');
            return idx >= 0 ? qName.substring(idx + 1) : qName;
        }

        private static String orEmpty(String value) {
            return value == null ? "" : value;
        }
    }
}
