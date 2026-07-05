package pro.softcom.aisentinel.application.pii.remediation.port.in;

import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;

import java.util.List;

/**
 * Batch of finding status transitions requested by a single actor.
 */
public record FindingStatusChangeCommand(List<StatusChange> changes, String actor) {

    public FindingStatusChangeCommand {
        if (changes == null) {
            throw new IllegalArgumentException("changes is required");
        }
        changes = List.copyOf(changes);
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
    }

    public record StatusChange(String findingId, FindingRemediationStatus targetStatus) {

        public StatusChange {
            if (findingId == null || findingId.isBlank()) {
                throw new IllegalArgumentException("findingId is required");
            }
            if (targetStatus == null) {
                throw new IllegalArgumentException("targetStatus is required");
            }
        }
    }
}
