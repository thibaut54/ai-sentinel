package pro.softcom.aisentinel.application.pii.remediation.port.in;

import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;

/**
 * Transitions every PENDING finding resolved from a criteria-based selection to a single
 * target status in one call, mirroring the job-submission contract. Used for bulk actions
 * (e.g. "mark treated") whose scope is the whole selection, not a paginated page slice.
 */
public record SelectionStatusChangeCommand(RemediationSelection selection,
                                           FindingRemediationStatus targetStatus,
                                           String actor) {

    public SelectionStatusChangeCommand {
        if (selection == null) {
            throw new IllegalArgumentException("selection is required");
        }
        if (targetStatus == null) {
            throw new IllegalArgumentException("targetStatus is required");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
    }
}
