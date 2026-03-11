package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanCheckpointEntity;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanCheckpointId;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DetectionCheckpointRepository extends
    JpaRepository<@NonNull ScanCheckpointEntity, @NonNull ScanCheckpointId> {

    Optional<ScanCheckpointEntity> findByScanIdAndSourceTypeAndSourceKey(String scanId, String sourceType, String sourceKey);

    List<ScanCheckpointEntity> findByScanIdOrderBySourceKey(String scanId);

    List<ScanCheckpointEntity> findBySourceTypeAndSourceKeyOrderByUpdatedAtDesc(String sourceType, String sourceKey);

    Optional<ScanCheckpointEntity> findFirstBySourceTypeAndSourceKeyOrderByUpdatedAtDesc(String sourceType, String sourceKey);

    /**
     * Finds the latest checkpoint for every source using a window function logic.
     * Business purpose: Aggregate the global state of all sources from their most recent scans.
     */
    @Query(value = """
        SELECT DISTINCT ON (source_type, source_key) *
        FROM scan_checkpoints
        ORDER BY source_type, source_key, updated_at DESC
        """, nativeQuery = true)
    List<ScanCheckpointEntity> findAllLatestCheckpoints();

    /**
     * Finds the latest checkpoint for every source of a given type using a window function logic.
     * Business purpose: Aggregate the state of all sources of a given type from their most recent scans.
     *
     * @param sourceType the datasource type to filter on
     * @return list of latest checkpoints (one per source key of the given type)
     */
    @Query(value = """
        SELECT DISTINCT ON (source_key) *
        FROM scan_checkpoints
        WHERE source_type = :sourceType
        ORDER BY source_key, updated_at DESC
        """, nativeQuery = true)
    List<ScanCheckpointEntity> findAllLatestCheckpointsBySourceType(@Param("sourceType") String sourceType);

    void deleteByScanId(String scanId);

    /**
     * Finds the most recent scan that is in RUNNING or PAUSED status.
     * Business purpose: Detect if there's an active multi-source scan that should be resumed
     * instead of starting a new one, preventing duplicate scanId generation.
     *
     * @return Optional containing the scanId of the most recent RUNNING or PAUSED scan
     */
    @Query("""
        SELECT s.scanId
        FROM ScanCheckpointEntity s
        WHERE s.status IN ('RUNNING', 'PAUSED')
        ORDER BY s.updatedAt DESC
        LIMIT 1
        """)
    Optional<String> findMostRecentActiveScanId();

    /**
     * Finds the checkpoint with RUNNING status for a given scan.
     * Business purpose: When pausing a scan, only the RUNNING checkpoint should be paused,
     * not COMPLETED or other status checkpoints.
     *
     * @param scanId the scan identifier
     * @return Optional containing the RUNNING checkpoint if found
     */
    @Query("""
        SELECT s
        FROM ScanCheckpointEntity s
        WHERE s.scanId = :scanId AND s.status = 'RUNNING'
        ORDER BY s.updatedAt DESC
        LIMIT 1
        """)
    Optional<ScanCheckpointEntity> findRunningScanCheckpoint(@Param("scanId") String scanId);

    /**
     * Deletes all scan checkpoints with RUNNING or PAUSED status for a given source type.
     * Business purpose: Clean up active scans when starting a fresh scan to prevent
     * data accumulation and ensure severity counts are accurate.
     *
     * @param sourceType the datasource type to filter on
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ScanCheckpointEntity s WHERE s.status IN ('RUNNING', 'PAUSED') AND s.sourceType = :sourceType")
    void deleteActiveScanCheckpointsBySourceType(@Param("sourceType") String sourceType);

    /**
     * Deletes active scan checkpoints (RUNNING or PAUSED status) for specific sources.
     * Business purpose: Clean up active scans for specific sources when starting a fresh selected scan.
     *
     * @param sourceType the datasource type to filter on
     * @param sourceKeys list of source keys to purge
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ScanCheckpointEntity s WHERE s.status IN ('RUNNING', 'PAUSED') AND s.sourceType = :sourceType AND s.sourceKey IN :sourceKeys")
    void deleteActiveScanCheckpointsForSources(@Param("sourceType") String sourceType, @Param("sourceKeys") List<String> sourceKeys);

    /**
     * Persists or updates a scan checkpoint using PostgreSQL's UPSERT mechanism.
     *
     * <p>This method implements atomic checkpoint persistence for scan resume functionality.
     * It ensures that scan progress (last processed content and attachment) is reliably saved,
     * enabling the system to resume scans from the exact point of interruption.
     *
     * <p><strong>Insert Behavior:</strong>
     * When no checkpoint exists for the given scan-source combination, creates a new record
     * with all provided values.
     *
     * <p><strong>Update Behavior (ON CONFLICT):</strong>
     * When a checkpoint already exists (scan_id, source_type, source_key conflict), applies conditional updates:
     * <ul>
     *   <li><strong>last_processed_content_id:</strong> Updated only if new value is non-null and non-empty,
     *       otherwise preserves existing value to prevent regression in scan progress</li>
     *   <li><strong>last_processed_attachment_name:</strong> Updated only if new value is non-null and non-empty,
     *       otherwise preserves existing value to maintain attachment processing state</li>
     *   <li><strong>status:</strong> Always updated to reflect current scan state</li>
     *   <li><strong>progress_percentage:</strong> Updated only when a non-null value is provided,
     *       otherwise preserves the existing non-null value to avoid regression of progress</li>
     *   <li><strong>updated_at:</strong> Always updated to track last checkpoint modification</li>
     * </ul>
     *
     * <p><strong>Concurrency Safety:</strong>
     * The ON CONFLICT clause provides database-level atomicity, preventing race conditions
     * when multiple scan processes attempt to update the same checkpoint simultaneously.
     *
     * <p><strong>Business Rule:</strong> The conditional update logic ensures that scan progress
     * never regresses - if a content/attachment has already been processed, empty or null values
     * from subsequent checkpoints will not overwrite this progress information.
     *
     * @param scanId unique identifier of the scan session
     * @param sourceType datasource type discriminator (CONFLUENCE, JIRA, SHAREPOINT)
     * @param sourceKey source identifier (space key, project key, site id, etc.)
     * @param contentId last successfully processed content ID (may be null during initial scan or between items)
     * @param attachmentName last successfully processed attachment name (may be null when no attachments are being processed)
     * @param status current scan status (e.g., "IN_PROGRESS", "COMPLETED", "FAILED")
     * @param progressPercentage current scan progress percentage (may be null)
     * @param updatedAt timestamp of checkpoint creation/update
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO scan_checkpoints (scan_id, source_type, source_key, last_processed_content_id, last_processed_attachment_name, status, progress_percentage, updated_at)
        VALUES (:scanId, :sourceType, :sourceKey, :contentId, :attachmentName, :status, :progressPercentage, :updatedAt)
        ON CONFLICT (scan_id, source_type, source_key) DO UPDATE
        SET last_processed_content_id = CASE WHEN :contentId IS NOT NULL AND :contentId != '' THEN :contentId ELSE scan_checkpoints.last_processed_content_id END,
            last_processed_attachment_name = CASE WHEN :attachmentName IS NOT NULL AND :attachmentName != '' THEN :attachmentName ELSE scan_checkpoints.last_processed_attachment_name END,
            status = :status,
            progress_percentage = CASE WHEN :progressPercentage IS NOT NULL THEN :progressPercentage ELSE scan_checkpoints.progress_percentage END,
            updated_at = :updatedAt
        """, nativeQuery = true)
    void upsertCheckpoint(@Param("scanId") String scanId,
                          @Param("sourceType") String sourceType,
                          @Param("sourceKey") String sourceKey,
                          @Param("contentId") String contentId,
                          @Param("attachmentName") String attachmentName,
                          @Param("status") String status,
                          @Param("progressPercentage") Double progressPercentage,
                          @Param("updatedAt") LocalDateTime updatedAt);
}
