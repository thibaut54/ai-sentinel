package pro.softcom.aisentinel.application.pii.remediation.usecase;

import lombok.RequiredArgsConstructor;
import pro.softcom.aisentinel.application.pii.remediation.port.in.TrackObfuscationJobPort;
import pro.softcom.aisentinel.application.pii.remediation.port.out.ObfuscationJobStore;
import pro.softcom.aisentinel.application.pii.remediation.port.out.RemediationConfigPort;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJob;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationDisabledException;

import java.util.Optional;

/**
 * Polling read model for redaction jobs: status, progression and per-finding outcomes.
 */
@RequiredArgsConstructor
public class TrackObfuscationJobUseCase implements TrackObfuscationJobPort {

    private final RemediationConfigPort remediationConfigPort;
    private final ObfuscationJobStore obfuscationJobStore;

    @Override
    public Optional<ObfuscationJob> findJob(String jobId) {
        requireEnabled();
        return obfuscationJobStore.findById(jobId);
    }

    private void requireEnabled() {
        if (!remediationConfigPort.isRemediationEnabled()) {
            throw new RemediationDisabledException("PII remediation is disabled by configuration");
        }
    }
}
