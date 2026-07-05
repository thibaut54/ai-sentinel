package pro.softcom.aisentinel.application.pii.remediation.port.in;

import java.util.List;

/**
 * Per-finding outcome of a batch status change: applied ids and individually
 * rejected changes with a non-sensitive reason.
 */
public record FindingStatusChangeResult(List<String> applied, List<RejectedChange> rejected) {

    public FindingStatusChangeResult {
        applied = applied == null ? List.of() : List.copyOf(applied);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    public record RejectedChange(String findingId, String reason) {
    }
}
