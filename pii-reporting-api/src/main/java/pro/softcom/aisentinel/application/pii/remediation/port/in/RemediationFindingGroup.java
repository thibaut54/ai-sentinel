package pro.softcom.aisentinel.application.pii.remediation.port.in;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;

import java.util.List;

/**
 * One accordion group of the remediation view (a PII type or a severity level).
 *
 * <p>{@code total} is the number of distinct-value findings in the group and
 * {@code occurrenceCount} the number of raw detections they collapsed; {@code selectedCount}
 * and {@code masterState} are computed over the whole group. Pagination is by group, so
 * {@code findings} always carries every member of the group.</p>
 */
@Builder(toBuilder = true)
public record RemediationFindingGroup(
        String key,
        String label,
        PersonallyIdentifiableInformationSeverity severity,
        long total,
        long occurrenceCount,
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
