package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

import java.util.List;

/**
 * Per-finding outcome of a batch status change.
 */
public record FindingStatusChangeResponseDto(List<String> applied, List<RejectedChangeDto> rejected) {

    public record RejectedChangeDto(String findingId, String reason) {
    }
}
