package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

/**
 * Body of {@code POST /findings/status/by-selection}: a criteria-based selection whose
 * resolved PENDING findings are all transitioned to {@code targetStatus} in one call.
 */
public record SelectionStatusChangeRequestDto(RemediationSelectionDto selection, String targetStatus) {
}
