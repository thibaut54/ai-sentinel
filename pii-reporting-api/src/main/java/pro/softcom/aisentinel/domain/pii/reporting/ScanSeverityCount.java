package pro.softcom.aisentinel.domain.pii.reporting;

/**
 * Immutable record representing aggregated PII counts for a specific scan and space.
 *
 * <p>Used for dashboard reporting and analytics, combining scan metadata with both
 * severity and legal classification statistics.
 *
 * @param scanId                Unique identifier of the scan
 * @param spaceKey              Confluence space key
 * @param counts                Aggregated severity counts (HIGH/MEDIUM/LOW)
 * @param classificationCounts  Aggregated counts by GDPR / nLPD legal classification
 */
public record ScanSeverityCount(
    String scanId,
    String spaceKey,
    SeverityCounts counts,
    ClassificationCounts classificationCounts
) {}
