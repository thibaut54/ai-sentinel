package pro.softcom.aisentinel.domain.pii.remediation;

/**
 * Thrown when a remediation operation is attempted while the
 * {@code pii.remediation.enabled} feature flag is off.
 */
public class RemediationDisabledException extends RuntimeException {

    public RemediationDisabledException(String message) {
        super(message);
    }
}
