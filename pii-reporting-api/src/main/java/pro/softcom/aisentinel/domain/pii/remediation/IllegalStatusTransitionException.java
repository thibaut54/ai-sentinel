package pro.softcom.aisentinel.domain.pii.remediation;

import lombok.Getter;

/**
 * Thrown when a finding remediation status transition violates the lifecycle rules
 * defined by {@link FindingRemediationStatus}.
 */
@Getter
public class IllegalStatusTransitionException extends RuntimeException {

    private final FindingRemediationStatus fromStatus;
    private final FindingRemediationStatus toStatus;

    public IllegalStatusTransitionException(FindingRemediationStatus fromStatus, FindingRemediationStatus toStatus) {
        super("Illegal finding remediation status transition from %s to %s".formatted(fromStatus, toStatus));
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }
}
