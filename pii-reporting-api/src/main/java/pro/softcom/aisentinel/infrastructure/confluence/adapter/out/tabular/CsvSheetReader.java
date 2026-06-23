package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SheetReader} implementation for delimiter-separated values (CSV) using Apache Commons CSV.
 * <p>
 * The delimiter is auto-detected from the first line (one of {@code ','}, {@code ';'} or tab,
 * defaulting to {@code ','}). The single resulting sheet is named {@code ""}. Bytes are decoded as
 * UTF-8 and a leading UTF-8 BOM is stripped. Quoted fields, embedded delimiters and newlines are
 * handled by Commons CSV. Trailing empty fields are kept as {@code ""}.
 * <p>
 * This class is stateless and has a public no-arg constructor.
 */
public class CsvSheetReader implements SheetReader {

    /** Safety cap on the total number of accumulated cells across all sheets. */
    private static final int MAX_CELLS = 1_000_000;

    private static final Logger LOG = LoggerFactory.getLogger(CsvSheetReader.class);

    /** UTF-8 byte order mark. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final char[] CANDIDATE_DELIMITERS = {',', ';', '\t'};

    @Override
    public List<SheetData> read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            return List.of();
        }
        String text = decode(bytes);
        if (text.isEmpty()) {
            return List.of();
        }
        char delimiter = detectDelimiter(firstLine(text));
        List<List<String>> rows = parseRows(text, delimiter);
        return List.of(new SheetData("", rows));
    }

    /**
     * Parses the text into rows, wrapping any Commons CSV runtime failure into an {@link IOException}.
     */
    private List<List<String>> parseRows(String text, char delimiter) throws IOException {
        CSVFormat format = CSVFormat.RFC4180.builder().setDelimiter(delimiter).get();
        List<List<String>> rows = new ArrayList<>();
        int cellCount = 0;
        try (Reader reader = new InputStreamReader(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {
            for (CSVRecord record : parser) {
                if (cellCount >= MAX_CELLS) {
                    LOG.warn("CSV cell cap {} reached; stopping read", MAX_CELLS);
                    break;
                }
                List<String> row = List.of(record.values()).stream().toList();
                rows.add(new ArrayList<>(row));
                cellCount += row.size();
            }
        } catch (RuntimeException ex) {
            throw new IOException("Failed to parse CSV content", ex);
        }
        return rows;
    }

    /** Decodes the bytes as UTF-8, stripping a leading UTF-8 BOM if present. */
    private String decode(byte[] bytes) {
        int offset = startsWithBom(bytes) ? UTF8_BOM.length : 0;
        return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
    }

    private boolean startsWithBom(byte[] bytes) {
        if (bytes.length < UTF8_BOM.length) {
            return false;
        }
        for (int i = 0; i < UTF8_BOM.length; i++) {
            if (bytes[i] != UTF8_BOM[i]) {
                return false;
            }
        }
        return true;
    }

    /** Returns the first line (up to the first CR or LF), or the whole text when single-line. */
    private String firstLine(String text) {
        int end = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                end = i;
                break;
            }
        }
        return text.substring(0, end);
    }

    /**
     * Picks the candidate delimiter with the most occurrences on the given line, defaulting to
     * {@code ','} on a tie or when none occur.
     */
    private char detectDelimiter(String line) {
        char best = ',';
        int bestCount = 0;
        for (char candidate : CANDIDATE_DELIMITERS) {
            int count = countChar(line, candidate);
            if (count > bestCount) {
                bestCount = count;
                best = candidate;
            }
        }
        return best;
    }

    private int countChar(String line, char target) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }
}
