package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabelStatus;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.entity.DiscoveredLabelEntity;

import java.util.List;

/**
 * Spring Data JPA repository for MINISTRAL discovered labels.
 * <p>
 * The occurrence upsert uses PostgreSQL {@code INSERT ... ON CONFLICT DO UPDATE}
 * so concurrent scans recording the same label never lose increments; new rows
 * default to {@code PENDING} and existing statuses are left untouched.
 */
@Repository
public interface DiscoveredLabelJpaRepository extends JpaRepository<DiscoveredLabelEntity, Long> {

    /**
     * Finds all discovered labels in the given lifecycle status.
     *
     * @param status the status to filter by
     * @return the matching entities
     */
    List<DiscoveredLabelEntity> findByStatus(DiscoveredLabelStatus status);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO ministral_discovered_label (label, occurrence_count, first_seen, last_seen, status)
        VALUES (:label, :count, now(), now(), 'PENDING')
        ON CONFLICT (label) DO UPDATE
        SET occurrence_count = ministral_discovered_label.occurrence_count + EXCLUDED.occurrence_count,
            last_seen = now()
        """, nativeQuery = true)
    void upsertOccurrence(@Param("label") String label, @Param("count") long count);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE ministral_discovered_label SET status = :status WHERE label = :label",
           nativeQuery = true)
    int updateStatusByLabel(@Param("label") String label, @Param("status") String status);
}
