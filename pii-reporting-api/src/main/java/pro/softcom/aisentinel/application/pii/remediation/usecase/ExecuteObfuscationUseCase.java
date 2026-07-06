package pro.softcom.aisentinel.application.pii.remediation.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.pii.remediation.port.in.ExecuteObfuscationPort;
import pro.softcom.aisentinel.application.pii.remediation.port.out.ObfuscationJobStore;
import pro.softcom.aisentinel.application.pii.remediation.port.out.RemediationConfigPort;
import pro.softcom.aisentinel.application.pii.remediation.service.ObfuscationJobRunner;
import pro.softcom.aisentinel.application.pii.remediation.service.SelectionResolver;
import pro.softcom.aisentinel.application.pii.remediation.service.SelectionResolver.ResolvedSelection;
import pro.softcom.aisentinel.domain.pii.remediation.AttachmentRedactionUnsupportedException;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJob;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJobAlreadyRunningException;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJobStatus;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationPlan;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationDisabledException;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;
import pro.softcom.aisentinel.domain.pii.remediation.SelectionOutdatedException;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Submits a redaction job: re-resolves the selection, rejects it when its checksum
 * diverged from the plan, freezes the resolved finding ids into the job row and hands
 * the execution to an asynchronous runner. At most one job runs per space, enforced
 * both by a pre-check and by the store's partial unique index (race-safe).
 */
@RequiredArgsConstructor
@Slf4j
public class ExecuteObfuscationUseCase implements ExecuteObfuscationPort {

    private final RemediationConfigPort remediationConfigPort;
    private final SelectionResolver selectionResolver;
    private final ObfuscationJobStore obfuscationJobStore;
    private final ObfuscationJobRunner jobRunner;
    private final Executor jobExecutor;
    private final Clock clock;

    @Override
    public String execute(ObfuscationSubmission submission) {
        requireEnabled();
        requirePageScope(submission.selection());
        ResolvedSelection resolved = selectionResolver.resolve(submission.selection());
        requireFreshChecksum(resolved, submission.selectionChecksum());
        requireRedactableFindings(resolved);
        requireNoActiveJob(submission.selection().spaceKey());
        ObfuscationJob job = newJob(submission, resolved);
        obfuscationJobStore.create(job);
        log.info("[PII_REMEDIATION] Redaction job {} created for space {} ({} findings)",
                job.id(), job.spaceKey(), job.total());
        jobExecutor.execute(() -> jobRunner.run(job, resolved));
        return job.id();
    }

    private void requireEnabled() {
        if (!remediationConfigPort.isRemediationEnabled()) {
            throw new RemediationDisabledException("PII remediation is disabled by configuration");
        }
    }

    private static void requirePageScope(RemediationSelection selection) {
        if (selection.attachmentName() != null) {
            throw new AttachmentRedactionUnsupportedException(
                    "Automatic redaction of attachments is not supported");
        }
    }

    private static void requireFreshChecksum(ResolvedSelection resolved, String submittedChecksum) {
        String currentChecksum = ObfuscationPlan.checksumOf(resolved.pageFindingIds());
        if (!currentChecksum.equals(submittedChecksum)) {
            throw new SelectionOutdatedException(
                    "The resolved selection changed since planning; re-plan before executing");
        }
    }

    private static void requireRedactableFindings(ResolvedSelection resolved) {
        if (resolved.pageFindings().isEmpty()) {
            throw new IllegalArgumentException("The selection resolves to no redactable finding");
        }
    }

    private void requireNoActiveJob(String spaceKey) {
        if (obfuscationJobStore.findActiveBySpace(spaceKey).isPresent()) {
            throw new ObfuscationJobAlreadyRunningException(spaceKey);
        }
    }

    private ObfuscationJob newJob(ObfuscationSubmission submission, ResolvedSelection resolved) {
        Instant now = clock.instant();
        return ObfuscationJob.builder()
                .id(UUID.randomUUID().toString())
                .spaceKey(submission.selection().spaceKey())
                .status(ObfuscationJobStatus.RUNNING)
                .submittedSelection(submission.selection())
                .resolvedFindingIds(resolved.pageFindingIds())
                .processed(0)
                .total(resolved.pageFindings().size())
                .actor(submission.actor())
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
