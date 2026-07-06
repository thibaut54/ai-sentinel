package pro.softcom.aisentinel.application.pii.remediation.port.in;

/**
 * Lifecycle breakdown of the findings in scope: {@code handled} covers both
 * {@code REDACTED} and {@code MANUALLY_HANDLED}.
 */
public record RemediationTotals(long pending, long handled, long falsePositive, long total) {

    public static RemediationTotals empty() {
        return new RemediationTotals(0, 0, 0, 0);
    }
}
