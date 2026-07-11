package pro.softcom.aisentinel.application.pii.remediation.service;

import pro.softcom.aisentinel.domain.pii.remediation.FindingReference;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;

/**
 * Resolves a criteria-based {@link RemediationSelection} against a single finding.
 *
 * <p>Rules: only {@code PENDING} findings are selectable; a checked type or severity
 * selects every matching finding; explicit inclusions add single findings; explicit
 * exclusions always win.</p>
 */
public class SelectionEvaluator {

    public boolean isSelected(EligibleFinding finding,
                              FindingRemediationStatus status,
                              RemediationSelection selection) {
        if (status != FindingRemediationStatus.PENDING) {
            return false;
        }
        if (selection.excludedFindingIds().contains(finding.findingId())) {
            return false;
        }
        return matchesCriteria(finding.reference(), selection)
                || selection.includedFindingIds().contains(finding.findingId());
    }

    private boolean matchesCriteria(FindingReference reference, RemediationSelection selection) {
        return selection.piiTypes().contains(reference.piiType())
                || selection.severities().contains(reference.severity());
    }
}
