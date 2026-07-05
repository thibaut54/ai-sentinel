package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.jpa.entity.ObfuscationJobEntity;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface ObfuscationJobRepository extends JpaRepository<ObfuscationJobEntity, String> {

    Optional<ObfuscationJobEntity> findFirstBySpaceKeyAndStatus(String spaceKey, String status);

    /**
     * Boot recovery: atomically marks every RUNNING job as INTERRUPTED so it can be
     * relaunched idempotently after a crash.
     *
     * @return number of rows updated
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
        UPDATE ObfuscationJobEntity j
        SET j.status = 'INTERRUPTED', j.updatedAt = :now
        WHERE j.status = 'RUNNING'
        """)
    int markRunningAsInterrupted(@Param("now") Instant now);
}
