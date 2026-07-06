package pro.softcom.aisentinel.application.pii.remediation.usecase;

import lombok.RequiredArgsConstructor;
import pro.softcom.aisentinel.application.pii.remediation.port.in.PlanObfuscationPort;
import pro.softcom.aisentinel.application.pii.remediation.port.out.RemediationConfigPort;
import pro.softcom.aisentinel.application.pii.remediation.service.EligibleFinding;
import pro.softcom.aisentinel.application.pii.remediation.service.SelectionResolver;
import pro.softcom.aisentinel.application.pii.remediation.service.SelectionResolver.ResolvedSelection;
import pro.softcom.aisentinel.domain.pii.remediation.FindingReference;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationPlan;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationDisabledException;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only preview of an obfuscation run: resolves the selection, separates page
 * findings from attachment exclusions and fingerprints the resolved set with a
 * checksum verified again at execution time. Never writes anything.
 */
@RequiredArgsConstructor
public class PlanObfuscationUseCase implements PlanObfuscationPort {

    private final RemediationConfigPort remediationConfigPort;
    private final SelectionResolver selectionResolver;

    @Override
    public ObfuscationPlan plan(RemediationSelection selection) {
        requireEnabled();
        ResolvedSelection resolved = selectionResolver.resolve(selection);
        return ObfuscationPlan.builder()
                .totalFindings(resolved.pageFindings().size())
                .bySeverity(severityBreakdownOf(resolved))
                .pagesImpacted(pagesImpactedBy(resolved))
                .falsePositivesReported((int) resolved.falsePositivesReported())
                .attachmentExclusions(resolved.attachmentFindings().size())
                .selectionChecksum(ObfuscationPlan.checksumOf(resolved.pageFindingIds()))
                .build();
    }

    private void requireEnabled() {
        if (!remediationConfigPort.isRemediationEnabled()) {
            throw new RemediationDisabledException("PII remediation is disabled by configuration");
        }
    }

    private static Map<PersonallyIdentifiableInformationSeverity, Integer> severityBreakdownOf(
            ResolvedSelection resolved) {
        return resolved.pageFindings().stream()
                .map(EligibleFinding::reference)
                .collect(Collectors.groupingBy(FindingReference::severity,
                        Collectors.summingInt(reference -> 1)));
    }

    private static int pagesImpactedBy(ResolvedSelection resolved) {
        return (int) resolved.pageFindings().stream()
                .map(finding -> finding.reference().pageId())
                .distinct()
                .count();
    }
}
