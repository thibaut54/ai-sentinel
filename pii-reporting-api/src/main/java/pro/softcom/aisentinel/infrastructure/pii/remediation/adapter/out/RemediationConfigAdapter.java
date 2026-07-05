package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.remediation.port.out.RemediationConfigPort;
import pro.softcom.aisentinel.infrastructure.config.properties.PiiRemediationProperties;

/**
 * Adapter for reading the PII remediation configuration.
 * Implements the out-port for hexagonal architecture compliance.
 */
@Component
@RequiredArgsConstructor
public class RemediationConfigAdapter implements RemediationConfigPort {

    private final PiiRemediationProperties properties;

    @Override
    public boolean isRemediationEnabled() {
        return properties.isEnabled();
    }
}
