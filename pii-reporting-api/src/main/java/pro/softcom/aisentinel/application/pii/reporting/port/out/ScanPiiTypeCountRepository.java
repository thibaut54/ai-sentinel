package pro.softcom.aisentinel.application.pii.reporting.port.out;

import pro.softcom.aisentinel.domain.pii.reporting.ScanPiiTypeCount;

import java.util.List;
import java.util.Map;

/**
 * Application out port to store and retrieve PII type occurrence counts for scans.
 *
 * <p>Business purpose: Maintains aggregated per-type statistics per scan and space
 * for performance-optimized dashboard display and reporting, avoiding expensive
 * real-time calculations across large datasets.
 */
public interface ScanPiiTypeCountRepository {

    /**
     * Atomically increments PII type counts for a given scan and space.
     *
     * <p>This operation must be thread-safe and support concurrent increments
     * from multiple scan workers processing the same space. If no record exists
     * for a given PII type, creates a new one with the provided delta value.
     *
     * @param scanId   Unique identifier of the scan
     * @param spaceKey Confluence space key
     * @param delta    PII type occurrence counts to add (must not be null)
     */
    void incrementCounts(String scanId, String spaceKey, Map<String, Integer> delta);

    /**
     * Retrieves PII type counts for a specific scan and space.
     *
     * @param scanId   Unique identifier of the scan
     * @param spaceKey Confluence space key
     * @return Occurrence count keyed by PII type (empty map when no detections)
     */
    Map<String, Integer> findCountsByScanIdAndSpaceKey(String scanId, String spaceKey);

    /**
     * Lists all PII type counts for a given scan, grouped by space.
     *
     * <p>Used for dashboard display showing per-space, per-type statistics for a scan.
     *
     * @param scanId Unique identifier of the scan
     * @return List of scan PII type counts (may be empty if scan has no data)
     */
    List<ScanPiiTypeCount> findByScanId(String scanId);

    /**
     * Deletes all PII type count records for a given scan.
     *
     * <p>Business purpose: Cleanup operation when a scan is deleted or restarted.
     *
     * @param scanId Unique identifier of the scan to delete
     */
    void deleteByScanId(String scanId);
}
