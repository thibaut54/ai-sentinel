package pro.softcom.aisentinel.domain.pii.remediation;

/**
 * Per-finding result of a redaction job execution. Skipped findings stay {@code PENDING}
 * and are reported in the job outcome; there is no silent failure.
 */
public enum RedactionOutcome {
    REDACTED,
    SKIPPED_STALE,
    SKIPPED_VALUE_NOT_FOUND,
    SKIPPED_ATTACHMENT,
    FAILED
}
