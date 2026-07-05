package pro.softcom.aisentinel.domain.pii.remediation;

import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Audit journal entry of a redaction job. The resolved finding ids are frozen at submission
 * time so the execution never re-evaluates the selection criteria; outcomes record the
 * per-finding result and never carry sensitive values.
 */
@Builder(toBuilder = true)
public record ObfuscationJob(
        String id,
        String spaceKey,
        ObfuscationJobStatus status,
        RemediationSelection submittedSelection,
        List<String> resolvedFindingIds,
        int processed,
        int total,
        Map<String, RedactionOutcome> outcomes,
        String actor,
        Instant createdAt,
        Instant updatedAt
) {

    public ObfuscationJob {
        requireNonBlank(id, "id");
        requireNonBlank(spaceKey, "spaceKey");
        requireNonBlank(actor, "actor");
        requireNonNull(status, "status");
        requireNonNull(submittedSelection, "submittedSelection");
        requireNonNull(createdAt, "createdAt");
        requireNonNull(updatedAt, "updatedAt");
        requireNonNegative(processed, "processed");
        requireNonNegative(total, "total");
        resolvedFindingIds = resolvedFindingIds == null ? List.of() : List.copyOf(resolvedFindingIds);
        outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static void requireNonNegative(int value, String fieldName) {
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
    }
}
