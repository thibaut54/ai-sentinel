package pro.softcom.aisentinel.application.pii.remediation.port.in;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;

import java.util.List;

/**
 * One accordion group of the remediation view (a PII type or a severity level).
 *
 * <p>{@code total}, {@code selectedCount} and {@code masterState} are computed over the
 * whole filtered scope; {@code findings} only carries the members of the current page.</p>
 */
@Builder(toBuilder = true)
public record RemediationFindingGroup(
        String key,
        String label,
        PersonallyIdentifiableInformationSeverity severity,
        long total,
        long selectedCount,
        MasterState masterState,
        List<RemediationFindingView> findings
) {

    public RemediationFindingGroup {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /**
     * Tri-state of the group master checkbox, evaluated against the selectable
     * ({@code PENDING}) findings of the group.
     */
    public enum MasterState {
        NONE,
        PARTIAL,
        ALL
    }
}
