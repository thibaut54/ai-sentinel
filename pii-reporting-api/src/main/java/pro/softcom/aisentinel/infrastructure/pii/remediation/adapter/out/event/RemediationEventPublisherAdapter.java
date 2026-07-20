package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import pro.softcom.aisentinel.application.pii.remediation.port.out.PublishRemediationEventPort;
import pro.softcom.aisentinel.domain.pii.remediation.SpaceFalsePositivesChanged;

/**
 * Infrastructure adapter for publishing remediation events.
 * Maps domain events to the Spring event publishing mechanism.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RemediationEventPublisherAdapter implements PublishRemediationEventPort {
    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishFalsePositivesChanged(SpaceFalsePositivesChanged event) {
        applicationEventPublisher.publishEvent(event);
        log.debug("Published false-positive change scanId={}, spaceKey={}", event.scanId(), event.spaceKey());
    }
}
