package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa;

import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanDetectorStatsEntity;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanDetectorStatsId;

import java.util.List;

/**
 * JPA repository for atomic per-detector scan statistics.
 *
 * <p>Detector stats are accumulated via PostgreSQL UPSERT, summing busy time,
 * processed characters and detections across the scan's analysis requests.
 */
@Repository
public interface ScanDetectorStatsJpaRepository extends
    JpaRepository<@NonNull ScanDetectorStatsEntity, @NonNull ScanDetectorStatsId> {

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO scan_detector_stats (scan_id, space_key, detector, busy_ms, chars_processed, detections, discarded, updated_at)
        VALUES (:scanId, :spaceKey, :detector, :busyMs, :chars, :detections, :discarded, now())
        ON CONFLICT (scan_id, space_key, detector) DO UPDATE
        SET busy_ms = scan_detector_stats.busy_ms + :busyMs,
            chars_processed = scan_detector_stats.chars_processed + :chars,
            detections = scan_detector_stats.detections + :detections,
            discarded = scan_detector_stats.discarded + :discarded,
            updated_at = now()
        """, nativeQuery = true)
    void accumulate(@Param("scanId") String scanId,
                    @Param("spaceKey") String spaceKey,
                    @Param("detector") String detector,
                    @Param("busyMs") long busyMs,
                    @Param("chars") long chars,
                    @Param("detections") int detections,
                    @Param("discarded") int discarded);

    List<ScanDetectorStatsEntity> findById_ScanIdAndId_SpaceKeyOrderById_Detector(String scanId, String spaceKey);
}
