package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanPiiTypeCountEntity;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanPiiTypeCountId;

import java.util.List;

/**
 * JPA repository for atomic persistence of PII type occurrence counts.
 *
 * <p>Provides thread-safe increment operations using PostgreSQL's UPSERT mechanism
 * (INSERT ... ON CONFLICT DO UPDATE) to ensure reliable count aggregation during
 * concurrent scan processing.
 */
@Repository
public interface ScanPiiTypeCountJpaRepository extends
    JpaRepository<@NonNull ScanPiiTypeCountEntity, @NonNull ScanPiiTypeCountId> {

    /**
     * Atomically increments the occurrence count for a scan-space-type combination using
     * PostgreSQL's UPSERT.
     *
     * <p><strong>Business Purpose:</strong>
     * Enables concurrent scan workers to safely increment per-type counts without race conditions.
     * Multiple workers may process different pages/attachments in the same space simultaneously,
     * and this operation ensures all increments are correctly applied without data loss.
     *
     * <p><strong>Insert Behavior (New Record):</strong>
     * When no record exists for the scan-space-type combination, creates a new entry with the
     * provided delta value as initial count.
     *
     * <p><strong>Update Behavior (Existing Record - ON CONFLICT):</strong>
     * When a record already exists (composite key conflict on scan_id + space_key + pii_type),
     * atomically adds the delta value to the existing occurrence count.
     *
     * <p><strong>Concurrency Safety:</strong>
     * The ON CONFLICT DO UPDATE clause provides database-level atomicity. Multiple concurrent
     * transactions attempting to increment the same record will be serialized by PostgreSQL's
     * row-level locking, preventing lost updates.
     *
     * @param scanId   Unique scan identifier (must not be null or blank)
     * @param spaceKey Confluence space key (must not be null or blank)
     * @param piiType  PII type code (must not be null or blank)
     * @param delta    Number of occurrences to add (may be 0, must not be negative)
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO scan_pii_type_counts (scan_id, space_key, pii_type, occurrence_count)
        VALUES (:scanId, :spaceKey, :piiType, :delta)
        ON CONFLICT (scan_id, space_key, pii_type) DO UPDATE
        SET occurrence_count = scan_pii_type_counts.occurrence_count + :delta
        """, nativeQuery = true)
    void incrementCounts(@Param("scanId") String scanId,
                         @Param("spaceKey") String spaceKey,
                         @Param("piiType") String piiType,
                         @Param("delta") int delta);

    /**
     * Retrieves all PII type count records for a given scan, ordered by space key.
     *
     * <p>Used for dashboard display showing per-space statistics for a scan.
     *
     * @param scanId Unique scan identifier
     * @return List of PII type count entities (may be empty)
     */
    List<ScanPiiTypeCountEntity> findById_ScanIdOrderById_SpaceKey(String scanId);

    /**
     * Retrieves all PII type count records for a given scan and space.
     *
     * @param scanId   Unique scan identifier
     * @param spaceKey Confluence space key
     * @return List of PII type count entities (may be empty)
     */
    List<ScanPiiTypeCountEntity> findById_ScanIdAndId_SpaceKey(String scanId, String spaceKey);

    /**
     * Deletes all PII type count records for a given scan.
     *
     * <p>Business purpose: Cleanup operation when a scan is deleted or restarted.
     *
     * @param scanId Unique scan identifier
     */
    void deleteById_ScanId(String scanId);
}
