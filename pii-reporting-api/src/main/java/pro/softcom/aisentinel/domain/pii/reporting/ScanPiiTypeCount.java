package pro.softcom.aisentinel.domain.pii.reporting;

import java.util.Map;

/**
 * Immutable record representing aggregated PII type counts for a specific scan and space.
 *
 * <p>Used for dashboard reporting and analytics, combining scan metadata with per-type statistics.
 *
 * @param scanId        Unique identifier of the scan
 * @param spaceKey      Confluence space key
 * @param countsByType  Occurrence count keyed by PII type code (e.g. EMAIL, PHONE_NUMBER)
 */
public record ScanPiiTypeCount(
    String scanId,
    String spaceKey,
    Map<String, Integer> countsByType
) {}
