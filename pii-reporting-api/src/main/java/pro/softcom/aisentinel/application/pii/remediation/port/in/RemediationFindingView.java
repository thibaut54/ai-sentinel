package pro.softcom.aisentinel.application.pii.remediation.port.in;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;

/**
 * Read-model row of a single finding. Carries only the masked context and metadata:
 * plaintext PII values never cross this model.
 */
@Builder(toBuilder = true)
public record RemediationFindingView(
        String findingId,
        String piiType,
        PersonallyIdentifiableInformationSeverity severity,
        String detector,
        double confidenceScore,
        String maskedContext,
        String pageId,
        String pageTitle,
        String attachmentName,
        FindingRemediationStatus status,
        boolean selected,
        boolean eligibleForRedaction,
        String ineligibilityReason
) {
}
