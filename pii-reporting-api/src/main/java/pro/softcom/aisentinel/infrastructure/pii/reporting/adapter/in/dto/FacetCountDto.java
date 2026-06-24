package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto;

/**
 * REST representation of a single filter option's facet metrics.
 *
 * @param nbSpaces         number of spaces for which this option applies
 * @param totalOccurrences total number of detections contributing to this option
 */
public record FacetCountDto(
    int nbSpaces,
    int totalOccurrences
) {
}
