package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Detects the header row of a sheet and resolves its labels.
 *
 * <p>RG1: the first non-empty row is the header (V1 default). RG4: a sheet is considered header-less
 * (so the file falls back to Tika) when it has no non-empty row, or when the first non-empty row
 * looks like data (all its non-blank cells are numeric). Empty sheets and header-only sheets yield no
 * data rows downstream.
 */
public final class TabularHeaderDetector {

    private TabularHeaderDetector() {
        throw new AssertionError("TabularHeaderDetector is a utility class and should not be instantiated");
    }

    /**
     * Header row position and its resolved (de-duplicated) labels.
     *
     * @param headerRowIndex index of the header row in the sheet's row list
     * @param labels resolved labels; blank entries stay empty and are rendered as {@code Colonne N}
     *               by the row serializer (RG3)
     */
    public record DetectedHeader(int headerRowIndex, List<String> labels) {
    }

    /**
     * Detects the header row of a sheet.
     *
     * @param rows the sheet rows (each a list of raw cell strings)
     * @return the detected header, or empty when no identifiable header exists (RG4)
     */
    public static Optional<DetectedHeader> detectHeader(List<List<String>> rows) {
        if (rows == null) {
            return Optional.empty();
        }
        for (int index = 0; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (hasNonBlankCell(row)) {
                return looksLikeHeader(row)
                    ? Optional.of(new DetectedHeader(index, resolveLabels(row)))
                    : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves header labels: trims them, keeps blanks empty, and disambiguates duplicate non-blank
     * labels with a {@code _2}, {@code _3}… suffix (computed once per sheet).
     */
    public static List<String> resolveLabels(List<String> headerRow) {
        List<String> result = new ArrayList<>(headerRow.size());
        Map<String, Integer> counts = new HashMap<>();
        for (String raw : headerRow) {
            String label = raw == null ? "" : raw.trim();
            if (label.isEmpty()) {
                result.add("");
                continue;
            }
            int occurrence = counts.merge(label, 1, Integer::sum);
            result.add(occurrence == 1 ? label : label + "_" + occurrence);
        }
        return result;
    }

    private static boolean hasNonBlankCell(List<String> row) {
        if (row == null) {
            return false;
        }
        return row.stream().anyMatch(cell -> cell != null && !cell.isBlank());
    }

    /**
     * A row is a header when at least one of its non-blank cells is not purely numeric: headers are
     * textual labels, whereas a row of pure numbers is data (RG4).
     */
    private static boolean looksLikeHeader(List<String> row) {
        return row.stream()
            .filter(cell -> cell != null && !cell.isBlank())
            .anyMatch(cell -> !isNumericLike(cell.trim()));
    }

    private static boolean isNumericLike(String value) {
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) {
                hasDigit = true;
            } else if (!isNumericPunctuation(c)) {
                return false;
            }
        }
        return hasDigit;
    }

    private static boolean isNumericPunctuation(char c) {
        return c == '+' || c == '-' || c == '.' || c == ',' || c == '\'' || c == '%'
            || Character.isSpaceChar(c);
    }
}
