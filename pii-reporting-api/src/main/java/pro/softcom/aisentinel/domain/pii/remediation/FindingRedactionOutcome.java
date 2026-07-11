package pro.softcom.aisentinel.domain.pii.remediation;

import lombok.Builder;

/**
 * Per-finding entry of a redaction job journal: what happened to the finding, its PII
 * type for display, and an optional reason. Never carries sensitive values.
 */
@Builder
public record FindingRedactionOutcome(
        String piiType,
        RedactionOutcome outcome,
        String reason
) {

    public FindingRedactionOutcome {
        if (piiType == null || piiType.isBlank()) {
            throw new IllegalArgumentException("piiType is required");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required");
        }
    }

    public static FindingRedactionOutcome of(String piiType, RedactionOutcome outcome) {
        return new FindingRedactionOutcome(piiType, outcome, null);
    }
}
