package pro.softcom.aisentinel.application.pii.remediation.port.out;

/**
 * Out-port for reading the PII remediation configuration.
 * Abstracts the configuration source from the application layer.
 */
public interface RemediationConfigPort {

    /**
     * Checks whether the remediation feature is enabled by configuration.
     *
     * @return true when bulk redaction and finding lifecycle operations are allowed
     */
    boolean isRemediationEnabled();
}
