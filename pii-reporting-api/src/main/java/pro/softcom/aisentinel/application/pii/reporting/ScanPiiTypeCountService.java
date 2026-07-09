package pro.softcom.aisentinel.application.pii.reporting;

import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanPiiTypeCountRepository;
import pro.softcom.aisentinel.domain.pii.reporting.ScanPiiTypeCount;

import java.util.List;
import java.util.Map;

/**
 * Application service managing PII type count persistence operations.
 *
 * <p>Business purpose: Orchestrates atomic increment and retrieval of per-type statistics
 * during scans, ensuring data consistency across concurrent scan workers while maintaining
 * performance through optimized aggregated storage.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Input validation for all scan and space identifiers</li>
 *   <li>Delegation to repository for atomic persistence operations</li>
 *   <li>Business rule enforcement for PII type count operations</li>
 * </ul>
 */
public class ScanPiiTypeCountService {

    private final ScanPiiTypeCountRepository repository;

    public ScanPiiTypeCountService(ScanPiiTypeCountRepository repository) {
        this.repository = repository;
    }

    /**
     * Atomically increments PII type counts for a scan and space.
     *
     * <p>Business rule: This operation must support concurrent execution from multiple
     * scan workers processing the same space without data loss or corruption.
     *
     * @param scanId Unique identifier of the scan (must not be null or blank)
     * @param spaceKey Confluence space key (must not be null or blank)
     * @param delta PII type occurrence counts to add (must not be null)
     * @throws IllegalArgumentException if scanId, spaceKey, or delta is null or blank
     */
    public void incrementCounts(String scanId, String spaceKey, Map<String, Integer> delta) {
        validateScanId(scanId);
        validateSpaceKey(spaceKey);
        validateDelta(delta);

        repository.incrementCounts(scanId, spaceKey, delta);
    }

    /**
     * Retrieves PII type counts for a specific scan and space.
     *
     * @param scanId Unique identifier of the scan (must not be null or blank)
     * @param spaceKey Confluence space key (must not be null or blank)
     * @return Occurrence count keyed by PII type (empty map when no detections)
     * @throws IllegalArgumentException if scanId or spaceKey is null or blank
     */
    public Map<String, Integer> getCounts(String scanId, String spaceKey) {
        validateScanId(scanId);
        validateSpaceKey(spaceKey);

        return repository.findCountsByScanIdAndSpaceKey(scanId, spaceKey);
    }

    /**
     * Lists all PII type counts for a given scan, grouped by space.
     *
     * <p>Business purpose: Provides dashboard data showing per-space, per-type statistics
     * for scan reporting and analytics.
     *
     * @param scanId Unique identifier of the scan (must not be null or blank)
     * @return List of scan PII type counts (may be empty if scan has no data)
     * @throws IllegalArgumentException if scanId is null or blank
     */
    public List<ScanPiiTypeCount> getCountsByScan(String scanId) {
        validateScanId(scanId);

        return repository.findByScanId(scanId);
    }

    /**
     * Deletes all PII type count records for a given scan.
     *
     * <p>Business purpose: Cleanup operation when a scan is deleted or needs to be restarted,
     * ensuring no stale data remains in the system.
     *
     * @param scanId Unique identifier of the scan to delete (must not be null or blank)
     * @throws IllegalArgumentException if scanId is null or blank
     */
    public void deleteCounts(String scanId) {
        validateScanId(scanId);

        repository.deleteByScanId(scanId);
    }

    private void validateScanId(String scanId) {
        if (scanId == null || scanId.isBlank()) {
            throw new IllegalArgumentException("scanId must not be null or blank");
        }
    }

    private void validateSpaceKey(String spaceKey) {
        if (spaceKey == null || spaceKey.isBlank()) {
            throw new IllegalArgumentException("spaceKey must not be null or blank");
        }
    }

    private void validateDelta(Map<String, Integer> delta) {
        if (delta == null) {
            throw new IllegalArgumentException("delta must not be null");
        }
    }
}
