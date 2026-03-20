package pro.softcom.aisentinel.application.pii.reporting;

import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanSeverityCountRepository;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ScanSeverityCount;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;

import java.util.List;
import java.util.Optional;

/**
 * Application service managing PII severity count persistence operations.
 *
 * <p>Business purpose: Orchestrates atomic increment and retrieval of severity statistics
 * during scans, ensuring data consistency across concurrent scan workers while maintaining
 * performance through optimized aggregated storage.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Input validation for all scan, source type, and source key identifiers</li>
 *   <li>Delegation to repository for atomic persistence operations</li>
 *   <li>Business rule enforcement for severity count operations</li>
 * </ul>
 */
public class ScanSeverityCountService {

    private final ScanSeverityCountRepository repository;

    public ScanSeverityCountService(ScanSeverityCountRepository repository) {
        this.repository = repository;
    }

    /**
     * Atomically increments severity counts for a scan and source.
     *
     * <p>Business rule: This operation must support concurrent execution from multiple
     * scan workers processing the same source without data loss or corruption.
     *
     * @param scanId     Unique identifier of the scan (must not be null or blank)
     * @param sourceType Datasource type discriminator (must not be null)
     * @param sourceKey  Source identifier (must not be null or blank)
     * @param delta      Severity counts to add (must not be null)
     * @throws IllegalArgumentException if any parameter is null or blank
     */
    public void incrementCounts(String scanId, SourceType sourceType, String sourceKey, SeverityCounts delta) {
        validateScanId(scanId);
        validateSourceType(sourceType);
        validateSourceKey(sourceKey);
        validateDelta(delta);

        repository.incrementCounts(scanId, sourceType, sourceKey, delta);
    }

    /**
     * Retrieves severity counts for a specific scan and source.
     *
     * @param scanId     Unique identifier of the scan (must not be null or blank)
     * @param sourceType Datasource type discriminator (must not be null)
     * @param sourceKey  Source identifier (must not be null or blank)
     * @return Severity counts if record exists, otherwise empty
     * @throws IllegalArgumentException if any parameter is null or blank
     */
    public Optional<SeverityCounts> getCounts(String scanId, SourceType sourceType, String sourceKey) {
        validateScanId(scanId);
        validateSourceType(sourceType);
        validateSourceKey(sourceKey);

        return repository.findByScanIdAndSource(scanId, sourceType, sourceKey);
    }

    /**
     * Lists all severity counts for a given scan, grouped by source.
     *
     * <p>Business purpose: Provides dashboard data showing per-source severity statistics
     * for scan reporting and analytics.
     *
     * @param scanId Unique identifier of the scan (must not be null or blank)
     * @return List of scan severity counts (may be empty if scan has no data)
     * @throws IllegalArgumentException if scanId is null or blank
     */
    public List<ScanSeverityCount> getCountsByScan(String scanId) {
        validateScanId(scanId);

        return repository.findByScanId(scanId);
    }

    /**
     * Deletes all severity count records for a given scan.
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

    private void validateSourceType(SourceType sourceType) {
        if (sourceType == null) {
            throw new IllegalArgumentException("sourceType must not be null");
        }
    }

    private void validateSourceKey(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("sourceKey must not be null or blank");
        }
    }

    private void validateDelta(SeverityCounts delta) {
        if (delta == null) {
            throw new IllegalArgumentException("delta must not be null");
        }
    }
}
