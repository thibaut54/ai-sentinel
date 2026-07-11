package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.remediation.port.out.RemediationConfigPort;
import pro.softcom.aisentinel.infrastructure.config.properties.PiiRemediationProperties;
import pro.softcom.aisentinel.infrastructure.config.properties.PiiReportingProperties;

/**
 * Adapter for reading the PII remediation configuration.
 * Implements the out-port for hexagonal architecture compliance.
 */
@Component
@RequiredArgsConstructor
public class RemediationConfigAdapter implements RemediationConfigPort {

    private final PiiRemediationProperties properties;
    private final PiiReportingProperties reportingProperties;

    /**
     * Redaction is only offered when reviewers can also see the plaintext values: without
     * {@code pii.reporting.allow-secret-reveal} they could not tell a genuine hit from a
     * false positive, so the whole remediation feature is disabled in that case.
     */
    @Override
    public boolean isRemediationEnabled() {
        return properties.isEnabled() && reportingProperties.isAllowSecretReveal();
    }
}
