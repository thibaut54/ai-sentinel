package pro.softcom.aisentinel.domain.pii.remediation;

/**
 * Lifecycle status of an obfuscation job. At most one {@code RUNNING} job may exist per space;
 * on application boot every {@code RUNNING} job is marked {@code INTERRUPTED} so it can be
 * relaunched idempotently.
 */
public enum ObfuscationJobStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    INTERRUPTED
}
