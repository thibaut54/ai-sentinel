package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping.Segment;

import java.util.ArrayList;
import java.util.List;

/**
 * Serializes one data row into the analysis fragment ({@code Header : value | ...}) and the context
 * fragment (raw values only), recording one value segment per emitted cell.
 *
 * <p>Business rules: RG2 skips empty cells (no fragment, no segment); RG3 renders a column without a
 * header as {@code Colonne N}; RG6 prefixes the sheet name on the analysis side only; RG7 copies each
 * value verbatim into both texts and anchors the segment on it. The {@code |} character inside a
 * value is kept as-is (the line-per-record format stays unambiguous because offsets are exact).
 *
 * <p>Returned offsets are relative to this row's fragments; the orchestrator rebases them globally.
 */
public final class TabularRowSerializer {

    private static final String ANALYSIS_SEPARATOR = " | ";
    private static final String LABEL_VALUE_SEPARATOR = " : ";
    private static final String CONTEXT_SEPARATOR = " ";
    private static final String COLUMN_FALLBACK_PREFIX = "Colonne ";

    private TabularRowSerializer() {
        throw new AssertionError("TabularRowSerializer is a utility class and should not be instantiated");
    }

    /**
     * Serializes a single data row.
     *
     * @param sheetName sheet name kept as analysis-side context (RG6); ignored when blank
     * @param labels resolved column labels (blank entries become {@code Colonne N})
     * @param cells the raw cell values of the row
     * @return the serialized row; {@link SerializedRow#isEmpty()} when no non-blank cell was emitted
     */
    public static SerializedRow serialize(String sheetName, List<String> labels, List<String> cells) {
        StringBuilder analysis = new StringBuilder();
        StringBuilder context = new StringBuilder();
        List<Segment> segments = new ArrayList<>();

        appendSheetPrefix(analysis, sheetName);

        boolean firstEmitted = true;
        for (int column = 0; column < cells.size(); column++) {
            String value = cells.get(column);
            if (value == null || value.isBlank()) {
                continue; // RG2: empty cell produces neither fragment nor segment
            }

            if (!firstEmitted) {
                analysis.append(ANALYSIS_SEPARATOR);
                context.append(CONTEXT_SEPARATOR);
            }

            analysis.append(resolveLabel(labels, column)).append(LABEL_VALUE_SEPARATOR);
            int analysisValueStart = analysis.length();
            analysis.append(value); // RG7: verbatim

            int contextValueStart = context.length();
            context.append(value); // RG7: verbatim, identical characters in both spaces

            segments.add(new Segment(analysisValueStart, value.length(), contextValueStart));
            firstEmitted = false;
        }

        if (firstEmitted) {
            return new SerializedRow("", "", List.of());
        }
        return new SerializedRow(analysis.toString(), context.toString(), segments);
    }

    private static void appendSheetPrefix(StringBuilder analysis, String sheetName) {
        if (sheetName != null && !sheetName.isBlank()) {
            analysis.append(sheetName.trim()).append(ANALYSIS_SEPARATOR);
        }
    }

    private static String resolveLabel(List<String> labels, int column) {
        if (labels != null && column < labels.size()) {
            String label = labels.get(column);
            if (label != null && !label.isEmpty()) {
                return label;
            }
        }
        return COLUMN_FALLBACK_PREFIX + (column + 1); // RG3
    }
}
