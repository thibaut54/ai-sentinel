package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.domain.confluence.extraction.ExtractedContent;
import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping;
import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping.Segment;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular.TabularHeaderDetector.DetectedHeader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates tabular extraction: routes to the right {@link SheetReader} by extension, detects each
 * sheet's header, serializes every data row into the analysis/context texts (RG1–RG7) and builds the
 * {@link ExtractedContent} trio (analysis text, context text, offset mapping).
 *
 * <p>The analysis text is capped at {@link #MAX_ANALYSIS_TEXT_CHARS}: when appending the next record
 * would exceed the cap, serialization stops on a row boundary and a WARN is logged. This is the single
 * tuning knob for the cap — there is no pre-existing extraction cap constant in this module, so this
 * constant IS the production setting; change it here to retune.
 */
@Component
public class TabularContentSerializer {

    private static final Logger log = LoggerFactory.getLogger(TabularContentSerializer.class);

    /**
     * Maximum number of characters of the analysis text. The analysis text is the larger of the two
     * (repeated headers inflate it ~30%), so capping it bounds the whole extraction. Truncation always
     * happens on a row boundary.
     */
    static final int MAX_ANALYSIS_TEXT_CHARS = 1_000_000;

    private static final String LINE_SEPARATOR = "\n";

    private final Map<String, SheetReader> readersByExtension = Map.of(
        "xlsx", new XlsxSheetReader(),
        "xls", new XlsSheetReader(),
        "csv", new CsvSheetReader(),
        "ods", new OdsSheetReader());

    /**
     * Serializes a tabular file into an {@link ExtractedContent}.
     *
     * @param extension the file extension (case-insensitive)
     * @param bytes the raw file content
     * @return the extracted content, or empty when the format is unsupported, the file is corrupted,
     *         or no identifiable header/record was found (caller falls back to Tika — RG4)
     */
    public Optional<ExtractedContent> serialize(String extension, byte[] bytes) {
        SheetReader reader = resolveReader(extension);
        if (reader == null || bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        List<SheetData> sheets = readSheets(reader, bytes, extension);
        if (sheets == null) {
            return Optional.empty();
        }
        return buildContent(sheets);
    }

    private SheetReader resolveReader(String extension) {
        if (extension == null) {
            return null;
        }
        return readersByExtension.get(extension.toLowerCase(Locale.ROOT).trim());
    }

    private List<SheetData> readSheets(SheetReader reader, byte[] bytes, String extension) {
        try {
            return reader.read(bytes);
        } catch (IOException | RuntimeException e) {
            log.warn("[TABULAR][READ_FAIL] ext={} - {} (falling back to Tika)", extension, e.getMessage());
            return null;
        }
    }

    private Optional<ExtractedContent> buildContent(List<SheetData> sheets) {
        Accumulator accumulator = new Accumulator();
        for (SheetData sheet : sheets) {
            if (!appendSheet(sheet, accumulator)) {
                break; // cap reached
            }
        }
        return accumulator.toExtractedContent();
    }

    /**
     * Appends one sheet's records to the accumulator.
     *
     * @return false when the cap was reached and the caller must stop iterating further sheets
     */
    private boolean appendSheet(SheetData sheet, Accumulator accumulator) {
        Optional<DetectedHeader> header = TabularHeaderDetector.detectHeader(sheet.rows());
        if (header.isEmpty()) {
            return true; // RG4 / empty sheet: nothing to emit, keep going
        }
        List<String> labels = header.orElseThrow().labels();
        List<List<String>> rows = sheet.rows();
        for (int i = header.orElseThrow().headerRowIndex() + 1; i < rows.size(); i++) {
            SerializedRow row = TabularRowSerializer.serialize(sheet.name(), labels, rows.get(i));
            if (row.isEmpty()) {
                continue;
            }
            if (!accumulator.tryAppend(row)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Mutable accumulator joining serialized rows with line separators, rebasing each row's relative
     * value segments into global offsets, and enforcing the analysis-text cap on a row boundary.
     */
    private static final class Accumulator {
        private final StringBuilder analysis = new StringBuilder();
        private final StringBuilder context = new StringBuilder();
        private final List<Segment> segments = new ArrayList<>();

        boolean tryAppend(SerializedRow row) {
            int separatorLength = analysis.isEmpty() ? 0 : LINE_SEPARATOR.length();
            if (!analysis.isEmpty()
                && analysis.length() + separatorLength + row.analysisFragment().length() > MAX_ANALYSIS_TEXT_CHARS) {
                log.warn("[TABULAR][CAP] analysis text reached {} chars, truncating on a row boundary",
                    MAX_ANALYSIS_TEXT_CHARS);
                return false;
            }
            if (!analysis.isEmpty()) {
                analysis.append(LINE_SEPARATOR);
                context.append(LINE_SEPARATOR);
            }
            int analysisBase = analysis.length();
            int contextBase = context.length();
            analysis.append(row.analysisFragment());
            context.append(row.contextFragment());
            for (Segment relative : row.valueSegments()) {
                segments.add(new Segment(
                    relative.analysisStart() + analysisBase,
                    relative.length(),
                    relative.contextStart() + contextBase));
            }
            return true;
        }

        Optional<ExtractedContent> toExtractedContent() {
            if (segments.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new ExtractedContent(
                analysis.toString(), context.toString(), new OffsetMapping(segments)));
        }
    }
}
