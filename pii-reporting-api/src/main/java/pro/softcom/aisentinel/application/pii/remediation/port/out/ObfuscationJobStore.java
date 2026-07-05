package pro.softcom.aisentinel.application.pii.remediation.port.out;

import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJob;

import java.util.Optional;

/**
 * Persistence port for the obfuscation job journal. The single-RUNNING-job-per-space
 * invariant is enforced by a partial unique index on the underlying table.
 */
public interface ObfuscationJobStore {

    void create(ObfuscationJob job);

    /**
     * Persists the current state of the job (progress, outcomes, status).
     */
    void update(ObfuscationJob job);

    Optional<ObfuscationJob> findById(String jobId);

    /**
     * Finds the {@code RUNNING} job of a space, if any.
     */
    Optional<ObfuscationJob> findActiveBySpace(String spaceKey);

    /**
     * Marks every {@code RUNNING} job as {@code INTERRUPTED} during boot recovery.
     *
     * @return the number of jobs transitioned
     */
    int markInterruptedOnBoot();
}
