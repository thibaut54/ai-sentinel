package pro.softcom.aisentinel.application.pii.reporting.port.out;

import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ScanSeverityCount;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;

import java.util.List;
import java.util.Optional;

/**
 * Application out port to store and retrieve PII severity counts for scans.
 *
 * <p>Business purpose: Maintains aggregated severity statistics per scan and source
 * for performance-optimized dashboard display and reporting, avoiding expensive
 * real-time calculations across large datasets.
 */
public interface ScanSeverityCountRepository {

    /**
     * Atomically increments severity counts for a given scan and source.
     *
     * <p>This operation must be thread-safe and support concurrent increments
     * from multiple scan workers processing the same source. If no record exists,
     * creates a new one with the provided delta values.
     *
     * @param scanId     Unique identifier of the scan
     * @param sourceType Datasource type discriminator
     * @param sourceKey  Source identifier (space key, project key, site id, etc.)
     * @param delta      Severity counts to add (must not be null)
     */
    void incrementCounts(String scanId, SourceType sourceType, String sourceKey, SeverityCounts delta);

    /**
     * Retrieves severity counts for a specific scan and source.
     *
     * @param scanId     Unique identifier of the scan
     * @param sourceType Datasource type discriminator
     * @param sourceKey  Source identifier (space key, project key, site id, etc.)
     * @return Severity counts if record exists, otherwise empty
     */
    Optional<SeverityCounts> findByScanIdAndSource(String scanId, SourceType sourceType, String sourceKey);

    /**
     * Lists all severity counts for a given scan, grouped by source.
     *
     * <p>Used for dashboard display showing per-source statistics for a scan.
     *
     * @param scanId Unique identifier of the scan
     * @return List of scan severity counts (may be empty if scan has no data)
     */
    List<ScanSeverityCount> findByScanId(String scanId);

    /**
     * Deletes all severity count records for a given scan.
     *
     * <p>Business purpose: Cleanup operation when a scan is deleted or restarted.
     *
     * @param scanId Unique identifier of the scan to delete
     */
    void deleteByScanId(String scanId);

    void deleteBySourceType(SourceType sourceType);

    void deleteBySourceTypeAndSourceKeys(SourceType sourceType, List<String> sourceKeys);
}
