package pro.softcom.aisentinel.application.pii.remediation.port.in;

/**
 * In-port for batch finding lifecycle transitions (false-positive reporting, manual
 * handling, restoration to pending).
 *
 * <p>{@code REDACTED} can never be reached through this port: it is reserved for
 * redaction jobs and is terminal.</p>
 */
public interface ChangeFindingStatusPort {

    /**
     * Applies the requested transitions, validating each one against the finding
     * lifecycle rules. Invalid changes are rejected individually and never abort
     * the rest of the batch.
     *
     * @throws pro.softcom.aisentinel.domain.pii.remediation.RemediationDisabledException
     *         when the feature flag is off
     */
    FindingStatusChangeResult changeStatuses(FindingStatusChangeCommand command);
}
