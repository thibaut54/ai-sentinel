package pro.softcom.aisentinel.domain.pii.reporting;

import pro.softcom.aisentinel.domain.pii.export.SourceType;

/**
 * Immutable record representing aggregated PII severity counts for a specific scan and source.
 *
 * <p>Used for dashboard reporting and analytics, combining scan metadata with severity statistics.
 *
 * @param scanId     Unique identifier of the scan
 * @param sourceType Datasource type discriminator
 * @param sourceKey  Source identifier (Confluence space key, Jira project key, SharePoint site ID)
 * @param counts     Aggregated severity counts for this scan-source combination
 */
public record ScanSeverityCount(
    String scanId,
    SourceType sourceType,
    String sourceKey,
    SeverityCounts counts
) {}
