package pro.softcom.aisentinel.application.pii.reporting.port.out;

/**
 * Out-port computing a stable keyed fingerprint of a detected PII value.
 *
 * <p>The fingerprint gives a finding an identity that survives re-scans without
 * exposing the value. Implementations must use a keyed MAC, never a bare hash:
 * low-entropy values (phone numbers, dates) would otherwise be recoverable by
 * enumeration.</p>
 */
public interface ValueFingerprintCalculator {

    /**
     * Computes the fingerprint of a detected value.
     *
     * @param value the plaintext value as detected by a scanner
     * @return a compact non-reversible fingerprint, or null when the value is null or blank
     */
    String fingerprint(String value);
}
