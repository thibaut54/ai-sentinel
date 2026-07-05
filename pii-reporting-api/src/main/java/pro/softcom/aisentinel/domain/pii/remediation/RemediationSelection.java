package pro.softcom.aisentinel.domain.pii.remediation;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;

import java.util.List;
import java.util.Set;

/**
 * Criteria-based selection of findings to remediate. The backend resolves these criteria
 * into concrete findings; the selection itself never carries resolved values.
 *
 * <p>Scope invariants: {@code spaceKey} is required; {@code pageId} narrows to a single page;
 * {@code attachmentName} narrows to a single attachment and requires {@code pageId}.</p>
 */
@Builder(toBuilder = true)
public record RemediationSelection(
        String spaceKey,
        String pageId,
        String attachmentName,
        List<String> piiTypes,
        List<PersonallyIdentifiableInformationSeverity> severities,
        Set<String> excludedFindingIds,
        Set<String> includedFindingIds
) {

    public RemediationSelection {
        requireNonBlank(spaceKey, "spaceKey");
        requireNotBlankWhenProvided(pageId, "pageId");
        requireNotBlankWhenProvided(attachmentName, "attachmentName");
        if (attachmentName != null && pageId == null) {
            throw new IllegalArgumentException("attachmentName requires pageId");
        }
        piiTypes = piiTypes == null ? List.of() : List.copyOf(piiTypes);
        severities = severities == null ? List.of() : List.copyOf(severities);
        excludedFindingIds = excludedFindingIds == null ? Set.of() : Set.copyOf(excludedFindingIds);
        includedFindingIds = includedFindingIds == null ? Set.of() : Set.copyOf(includedFindingIds);
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static void requireNotBlankWhenProvided(String value, String fieldName) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank when provided");
        }
    }
}
