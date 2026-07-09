package pro.softcom.aisentinel.application.pii.remediation.service;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.remediation.FindingReference;

/**
 * A remediation-eligible finding resolved from scan events: its stable identity plus the
 * display metadata. {@code sensitiveValue} and {@code sensitiveContext} carry the plaintext
 * value and its surrounding line, and are only populated when the events are read in decrypted
 * mode (remediation review, gated by {@code pii.reporting.allow-secret-reveal}); they stay null
 * otherwise.
 *
 * <p>{@code occurrenceCount} is the number of raw detections that collapsed into this
 * finding: the same value detected several times on the same item (by the same detector)
 * shares one identity, and redacting it rewrites every occurrence at once.</p>
 */
@Builder(toBuilder = true)
public record EligibleFinding(
        String findingId,
        FindingReference reference,
        double confidence,
        String piiTypeLabel,
        String maskedContext,
        String sensitiveValue,
        String sensitiveContext,
        String pageTitle,
        int occurrenceCount
) {
}
