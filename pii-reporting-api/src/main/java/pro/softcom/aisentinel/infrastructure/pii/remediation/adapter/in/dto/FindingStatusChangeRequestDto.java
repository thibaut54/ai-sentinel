package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

import java.util.List;

/**
 * Body of {@code POST /findings/status}: a batch of lifecycle transitions.
 */
public record FindingStatusChangeRequestDto(List<FindingStatusChangeDto> changes) {

    public record FindingStatusChangeDto(String findingId, String targetStatus) {
    }
}
