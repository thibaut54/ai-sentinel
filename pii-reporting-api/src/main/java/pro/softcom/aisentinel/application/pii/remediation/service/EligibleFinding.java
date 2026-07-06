package pro.softcom.aisentinel.application.pii.remediation.service;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.remediation.FindingReference;

/**
 * A remediation-eligible finding resolved from scan events: its stable identity plus the
 * display metadata safe to expose (masked context only, never plaintext values).
 */
@Builder(toBuilder = true)
public record EligibleFinding(
        String findingId,
        FindingReference reference,
        double confidence,
        String piiTypeLabel,
        String maskedContext,
        String pageTitle
) {
}
