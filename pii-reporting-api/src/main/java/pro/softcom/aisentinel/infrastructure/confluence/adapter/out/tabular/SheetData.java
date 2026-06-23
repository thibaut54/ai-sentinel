package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import java.util.List;

/**
 * Reader-agnostic representation of one sheet of a tabular file.
 *
 * <p>Each row is the list of its cell values as raw strings (verbatim — RG7). Empty cells are kept
 * as empty strings so column positions stay aligned with the header. Header detection and the
 * "header : value" serialization are applied downstream by {@code TabularHeaderDetector} and
 * {@code TabularRowSerializer}, so concrete {@link SheetReader}s only parse cells.
 *
 * @param name sheet name (used as analysis-side context — RG6); may be blank for single-sheet formats
 * @param rows the rows, each a list of raw cell strings
 */
public record SheetData(String name, List<List<String>> rows) {
}
