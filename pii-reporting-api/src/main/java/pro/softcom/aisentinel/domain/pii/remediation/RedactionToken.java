package pro.softcom.aisentinel.domain.pii.remediation;

/**
 * Builds the redaction token written in place of a PII value, e.g. {@code [EMAIL_ADDRESS]}.
 *
 * <p>Single source of truth for the masking token convention:
 * {@code PiiMaskingUtils.token} (application layer) delegates here, since the domain
 * layer cannot depend on the application layer.</p>
 */
public final class RedactionToken {

    private static final String UNKNOWN_TYPE = "UNKNOWN";

    private RedactionToken() {
    }

    public static String forType(String piiType) {
        String type = isUnknown(piiType) ? UNKNOWN_TYPE : piiType;
        return "[" + type + "]";
    }

    private static boolean isUnknown(String piiType) {
        return piiType == null || piiType.isBlank() || "null".equals(piiType);
    }
}
