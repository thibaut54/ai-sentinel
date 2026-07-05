package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanSpaceStatsEntity;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanSpaceStatsId;

import java.time.Instant;

/**
 * JPA repository for atomic per-space scan statistics.
 *
 * <p>All increments use PostgreSQL UPSERT (INSERT ... ON CONFLICT DO UPDATE) so
 * concurrent attachment scans on the same (scan, space) row never lose updates.
 *
 * <p>Every INSERT provides explicit zero values for all counter columns: the
 * table may have been created by Hibernate ddl-auto (NOT NULL, no SQL DEFAULT),
 * so a partial column list would violate the NOT NULL constraints.
 */
@Repository
public interface ScanSpaceStatsJpaRepository extends
    JpaRepository<@NonNull ScanSpaceStatsEntity, @NonNull ScanSpaceStatsId> {

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO scan_space_stats (scan_id, space_key, started_at, finished_at, pages_scanned, pages_failed,
                                      page_chars, attachments_scanned, attachments_failed, attachment_chars, updated_at)
        VALUES (:scanId, :spaceKey, :startedAt, NULL, 0, 0, 0, 0, 0, 0, now())
        ON CONFLICT (scan_id, space_key) DO UPDATE
        SET started_at = COALESCE(scan_space_stats.started_at, EXCLUDED.started_at),
            updated_at = now()
        """, nativeQuery = true)
    void markStarted(@Param("scanId") String scanId,
                     @Param("spaceKey") String spaceKey,
                     @Param("startedAt") Instant startedAt);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO scan_space_stats (scan_id, space_key, started_at, finished_at, pages_scanned, pages_failed,
                                      page_chars, attachments_scanned, attachments_failed, attachment_chars, updated_at)
        VALUES (:scanId, :spaceKey, NULL, :finishedAt, 0, 0, 0, 0, 0, 0, now())
        ON CONFLICT (scan_id, space_key) DO UPDATE
        SET finished_at = :finishedAt,
            updated_at = now()
        """, nativeQuery = true)
    void markFinished(@Param("scanId") String scanId,
                      @Param("spaceKey") String spaceKey,
                      @Param("finishedAt") Instant finishedAt);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO scan_space_stats (scan_id, space_key, started_at, finished_at, pages_scanned, pages_failed,
                                      page_chars, attachments_scanned, attachments_failed, attachment_chars, updated_at)
        VALUES (:scanId, :spaceKey, NULL, NULL, 1, 0, :charCount, 0, 0, 0, now())
        ON CONFLICT (scan_id, space_key) DO UPDATE
        SET pages_scanned = scan_space_stats.pages_scanned + 1,
            page_chars = scan_space_stats.page_chars + :charCount,
            updated_at = now()
        """, nativeQuery = true)
    void incrementPageScanned(@Param("scanId") String scanId,
                              @Param("spaceKey") String spaceKey,
                              @Param("charCount") long charCount);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO scan_space_stats (scan_id, space_key, started_at, finished_at, pages_scanned, pages_failed,
                                      page_chars, attachments_scanned, attachments_failed, attachment_chars, updated_at)
        VALUES (:scanId, :spaceKey, NULL, NULL, 0, 1, 0, 0, 0, 0, now())
        ON CONFLICT (scan_id, space_key) DO UPDATE
        SET pages_failed = scan_space_stats.pages_failed + 1,
            updated_at = now()
        """, nativeQuery = true)
    void incrementPageFailed(@Param("scanId") String scanId,
                             @Param("spaceKey") String spaceKey);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO scan_space_stats (scan_id, space_key, started_at, finished_at, pages_scanned, pages_failed,
                                      page_chars, attachments_scanned, attachments_failed, attachment_chars, updated_at)
        VALUES (:scanId, :spaceKey, NULL, NULL, 0, 0, 0, 1, 0, :charCount, now())
        ON CONFLICT (scan_id, space_key) DO UPDATE
        SET attachments_scanned = scan_space_stats.attachments_scanned + 1,
            attachment_chars = scan_space_stats.attachment_chars + :charCount,
            updated_at = now()
        """, nativeQuery = true)
    void incrementAttachmentScanned(@Param("scanId") String scanId,
                                    @Param("spaceKey") String spaceKey,
                                    @Param("charCount") long charCount);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO scan_space_stats (scan_id, space_key, started_at, finished_at, pages_scanned, pages_failed,
                                      page_chars, attachments_scanned, attachments_failed, attachment_chars, updated_at)
        VALUES (:scanId, :spaceKey, NULL, NULL, 0, 0, 0, 0, 1, 0, now())
        ON CONFLICT (scan_id, space_key) DO UPDATE
        SET attachments_failed = scan_space_stats.attachments_failed + 1,
            updated_at = now()
        """, nativeQuery = true)
    void incrementAttachmentFailed(@Param("scanId") String scanId,
                                   @Param("spaceKey") String spaceKey);
}
