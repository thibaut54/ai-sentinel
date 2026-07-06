package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single finding row of the remediation view. Deliberately has no field for the
 * plaintext value or context: only the masked context ever crosses this API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemediationFindingDto(
        String findingId,
        String piiType,
        String severity,
        String detector,
        double confidenceScore,
        String maskedContext,
        String pageId,
        String pageTitle,
        String attachmentName,
        String status,
        boolean selected,
        boolean eligibleForRedaction,
        String ineligibilityReason
) {
}
