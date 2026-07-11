package pro.softcom.aisentinel.domain.pii.reporting;

/**
 * Aggregated facet metrics for a single filter option (PII type, severity bucket or status).
 *
 * @param spaceCount         number of spaces for which this option applies
 * @param totalOccurrences total number of detections contributing to this option
 */
public record FacetCount(
    int spaceCount,
    int totalOccurrences
) {
}
