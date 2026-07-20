package pro.softcom.aisentinel.application.pii.remediation.port.out;

import pro.softcom.aisentinel.domain.pii.remediation.SpaceFalsePositivesChanged;

/**
 * Out-port for publishing remediation domain events to interested listeners.
 */
public interface PublishRemediationEventPort {

    void publishFalsePositivesChanged(SpaceFalsePositivesChanged event);
}
