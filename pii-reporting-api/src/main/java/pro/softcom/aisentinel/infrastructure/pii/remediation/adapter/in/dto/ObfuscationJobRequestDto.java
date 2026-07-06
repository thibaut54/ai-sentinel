package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

/**
 * Job submission: the selection to redact plus the checksum returned by the plan,
 * verified server-side against the re-resolved selection.
 */
public record ObfuscationJobRequestDto(
        RemediationSelectionDto selection,
        String selectionChecksum
) {
}
