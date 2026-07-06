package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.remediation.port.out.ObfuscationJobStore;

/**
 * Crash recovery: jobs left {@code RUNNING} by a previous process are marked
 * {@code INTERRUPTED} at boot so they can be relaunched idempotently (already
 * redacted findings are excluded by the resolver and written tokens never re-match).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ObfuscationJobBootRecovery {

    private final ObfuscationJobStore obfuscationJobStore;

    @EventListener(ApplicationReadyEvent.class)
    public void markInterruptedJobs() {
        int interrupted = obfuscationJobStore.markInterruptedOnBoot();
        if (interrupted > 0) {
            log.warn("[PII_REMEDIATION] {} redaction job(s) found RUNNING at boot and marked INTERRUPTED",
                    interrupted);
        }
    }
}
