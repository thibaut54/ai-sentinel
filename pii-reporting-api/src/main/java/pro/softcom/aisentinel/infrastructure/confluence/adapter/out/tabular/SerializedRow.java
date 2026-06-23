package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular;

import pro.softcom.aisentinel.domain.confluence.extraction.OffsetMapping;

import java.util.List;

/**
 * Serialization of a single data row into the two text spaces, with the value correspondence.
 *
 * <p>Offsets in {@code valueSegments} are RELATIVE to this row's fragments; the orchestrator rebases
 * them to global document offsets when it joins rows with line separators.
 *
 * @param analysisFragment one record as {@code Header : value | ...} (sent to the detector)
 * @param contextFragment the raw values only, space-separated (rendered in the report)
 * @param valueSegments one segment per emitted value, mapping its analysis-fragment range to its
 *                      context-fragment range (the value is byte-identical in both — RG7)
 */
public record SerializedRow(String analysisFragment, String contextFragment,
                            List<OffsetMapping.Segment> valueSegments) {

    /** An empty row (all cells blank): produces neither fragment nor segment (RG2). */
    public boolean isEmpty() {
        return valueSegments.isEmpty();
    }
}
