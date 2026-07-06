package pro.softcom.aisentinel.application.pii.remediation.usecase;

import lombok.RequiredArgsConstructor;
import pro.softcom.aisentinel.application.pii.remediation.port.in.ChangeFindingStatusPort;
import pro.softcom.aisentinel.application.pii.remediation.port.in.FindingStatusChangeCommand;
import pro.softcom.aisentinel.application.pii.remediation.port.in.FindingStatusChangeCommand.StatusChange;
import pro.softcom.aisentinel.application.pii.remediation.port.in.FindingStatusChangeResult;
import pro.softcom.aisentinel.application.pii.remediation.port.in.FindingStatusChangeResult.RejectedChange;
import pro.softcom.aisentinel.application.pii.remediation.port.in.SelectionStatusChangeCommand;
import pro.softcom.aisentinel.application.pii.remediation.port.out.FindingRemediationStore;
import pro.softcom.aisentinel.application.pii.remediation.port.out.RemediationConfigPort;
import pro.softcom.aisentinel.application.pii.remediation.service.EligibleFinding;
import pro.softcom.aisentinel.application.pii.remediation.service.ScanEventFindingResolver;
import pro.softcom.aisentinel.application.pii.remediation.service.SelectionResolver;
import pro.softcom.aisentinel.application.pii.remediation.service.SelectionResolver.ResolvedSelection;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;
import pro.softcom.aisentinel.domain.pii.remediation.FindingReference;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediation;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationDisabledException;
import pro.softcom.aisentinel.domain.pii.reporting.LastScanMeta;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Batch finding lifecycle transitions: false-positive reporting (immediate), manual
 * handling, restoration to {@code PENDING}. Each change is validated against the domain
 * transition rules and rejected individually on violation; the batch never aborts.
 *
 * <p>A finding without a projection row is implicitly {@code PENDING}; its first
 * transition materialises the row with denormalised fields resolved from the latest
 * scan events (encrypted read, no plaintext access).</p>
 */
@RequiredArgsConstructor
public class ChangeFindingStatusUseCase implements ChangeFindingStatusPort {

    private static final String REASON_UNKNOWN_FINDING = "finding not found in latest scan";
    private static final String REASON_REDACTED_RESERVED = "REDACTED is reserved for redaction jobs";

    private final RemediationConfigPort remediationConfigPort;
    private final ScanResultQuery scanResultQuery;
    private final FindingRemediationStore findingRemediationStore;
    private final ScanEventFindingResolver findingResolver;
    private final SelectionResolver selectionResolver;
    private final Clock clock;

    @Override
    public FindingStatusChangeResult changeStatusesBySelection(SelectionStatusChangeCommand command) {
        requireEnabled();
        ResolvedSelection resolved = selectionResolver.resolve(command.selection());
        List<StatusChange> changes = Stream.concat(
                        resolved.pageFindings().stream(), resolved.attachmentFindings().stream())
                .map(finding -> new StatusChange(finding.findingId(), command.targetStatus()))
                .toList();
        return changeStatuses(new FindingStatusChangeCommand(changes, command.actor()));
    }

    @Override
    public FindingStatusChangeResult changeStatuses(FindingStatusChangeCommand command) {
        requireEnabled();
        BatchContext context = prepareContext(command);
        List<String> applied = new ArrayList<>();
        List<RejectedChange> rejected = new ArrayList<>();
        Map<String, FindingRemediation> toPersist = new LinkedHashMap<>();
        for (StatusChange change : command.changes()) {
            Outcome outcome = apply(change, command.actor(), context);
            if (outcome.rejection() != null) {
                rejected.add(outcome.rejection());
            } else {
                context.rows().put(outcome.row().findingId(), outcome.row());
                toPersist.put(outcome.row().findingId(), outcome.row());
                applied.add(outcome.row().findingId());
            }
        }
        persist(toPersist);
        return new FindingStatusChangeResult(applied, rejected);
    }

    private void requireEnabled() {
        if (!remediationConfigPort.isRemediationEnabled()) {
            throw new RemediationDisabledException("PII remediation is disabled by configuration");
        }
    }

    private BatchContext prepareContext(FindingStatusChangeCommand command) {
        Set<String> ids = command.changes().stream()
                .map(StatusChange::findingId)
                .collect(Collectors.toSet());
        Map<String, FindingRemediation> rows = loadRows(ids);
        if (rows.keySet().containsAll(ids)) {
            return new BatchContext(rows, Map.of(), null);
        }
        return withResolvedFindings(rows);
    }

    private Map<String, FindingRemediation> loadRows(Set<String> ids) {
        if (ids.isEmpty()) {
            return new HashMap<>();
        }
        return findingRemediationStore.findByIds(ids).stream()
                .collect(Collectors.toMap(FindingRemediation::findingId, Function.identity(),
                        (first, second) -> first, HashMap::new));
    }

    private BatchContext withResolvedFindings(Map<String, FindingRemediation> rows) {
        Optional<LastScanMeta> latestScan = scanResultQuery.findLatestScan();
        if (latestScan.isEmpty()) {
            return new BatchContext(rows, Map.of(), null);
        }
        String scanId = latestScan.get().scanId();
        Map<String, EligibleFinding> eligibleById = findingResolver
                .resolve(scanResultQuery.listItemEventsEncrypted(scanId))
                .findings().stream()
                .collect(Collectors.toMap(EligibleFinding::findingId, Function.identity(),
                        (first, second) -> first));
        return new BatchContext(rows, eligibleById, scanId);
    }

    private Outcome apply(StatusChange change, String actor, BatchContext context) {
        if (change.targetStatus() == FindingRemediationStatus.REDACTED) {
            return Outcome.rejected(change.findingId(), REASON_REDACTED_RESERVED);
        }
        FindingRemediation row = context.rows().get(change.findingId());
        if (row != null) {
            return transitionExisting(row, change, actor);
        }
        return transitionNew(change, actor, context);
    }

    private Outcome transitionExisting(FindingRemediation row, StatusChange change, String actor) {
        if (!row.status().canTransitionTo(change.targetStatus())) {
            return Outcome.rejected(change.findingId(),
                    illegalTransitionReason(row.status(), change.targetStatus()));
        }
        return Outcome.applied(row.toBuilder()
                .status(change.targetStatus())
                .statusReason(null)
                .actor(actor)
                .occurredAt(clock.instant())
                .build());
    }

    private Outcome transitionNew(StatusChange change, String actor, BatchContext context) {
        EligibleFinding finding = context.eligibleById().get(change.findingId());
        if (finding == null) {
            return Outcome.rejected(change.findingId(), REASON_UNKNOWN_FINDING);
        }
        if (!FindingRemediationStatus.PENDING.canTransitionTo(change.targetStatus())) {
            return Outcome.rejected(change.findingId(),
                    illegalTransitionReason(FindingRemediationStatus.PENDING, change.targetStatus()));
        }
        return Outcome.applied(newRow(finding, context.scanId(), change.targetStatus(), actor));
    }

    private FindingRemediation newRow(EligibleFinding finding, String scanId,
                                      FindingRemediationStatus target, String actor) {
        FindingReference reference = finding.reference();
        return FindingRemediation.builder()
                .findingId(finding.findingId())
                .scanId(scanId)
                .spaceKey(reference.spaceKey())
                .pageId(reference.pageId())
                .attachmentName(reference.attachmentName())
                .piiType(reference.piiType())
                .severity(reference.severity())
                .detector(reference.detector())
                .status(target)
                .actor(actor)
                .occurredAt(clock.instant())
                .build();
    }

    private void persist(Map<String, FindingRemediation> toPersist) {
        if (!toPersist.isEmpty()) {
            findingRemediationStore.upsertAll(toPersist.values());
        }
    }

    private static String illegalTransitionReason(FindingRemediationStatus from, FindingRemediationStatus to) {
        return "illegal transition from %s to %s".formatted(from, to);
    }

    private record BatchContext(Map<String, FindingRemediation> rows,
                                Map<String, EligibleFinding> eligibleById,
                                String scanId) {
    }

    private record Outcome(FindingRemediation row, RejectedChange rejection) {

        static Outcome applied(FindingRemediation row) {
            return new Outcome(row, null);
        }

        static Outcome rejected(String findingId, String reason) {
            return new Outcome(null, new RejectedChange(findingId, reason));
        }
    }
}
