package pro.softcom.aisentinel.application.pii.remediation.port.in;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;

/**
 * Search request for the remediation read model.
 *
 * <p>Scope semantics: {@code spaceKey} alone covers the whole space; adding {@code pageId}
 * narrows to that page and its attachments; adding {@code attachmentName} narrows to a
 * single attachment.</p>
 */
@Builder(toBuilder = true)
public record RemediationFindingsQuery(
        String spaceKey,
        String pageId,
        String attachmentName,
        GroupBy groupBy,
        StatusFilter statusFilter,
        String searchText,
        String itemFilter,
        int page,
        int pageSize,
        RemediationSelection selection
) {

    public RemediationFindingsQuery {
        requireNonBlank(spaceKey, "spaceKey");
        requireNonNull(groupBy, "groupBy");
        requireNonNull(statusFilter, "statusFilter");
        requireNonNull(selection, "selection");
        if (attachmentName != null && pageId == null) {
            throw new IllegalArgumentException("attachmentName requires pageId");
        }
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be at least 1");
        }
    }

    public enum GroupBy {
        TYPE,
        SEVERITY
    }

    /**
     * Lifecycle facet filter of the view; {@code HANDLED} covers both
     * {@code REDACTED} and {@code MANUALLY_HANDLED}.
     */
    public enum StatusFilter {
        ALL,
        PENDING,
        HANDLED,
        FALSE_POSITIVE
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private static void requireNonNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }
}
