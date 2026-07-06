package pro.softcom.aisentinel.domain.pii.remediation;

/**
 * Thrown when a redaction job is submitted for a space that already has a
 * {@code RUNNING} job (mutual exclusion: at most one active job per space).
 */
public class ObfuscationJobAlreadyRunningException extends RuntimeException {

    public ObfuscationJobAlreadyRunningException(String spaceKey) {
        super("An obfuscation job is already running for space " + spaceKey);
    }
}
