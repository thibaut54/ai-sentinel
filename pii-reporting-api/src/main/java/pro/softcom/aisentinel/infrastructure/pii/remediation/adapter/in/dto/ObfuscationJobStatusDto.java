package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Polling view of a redaction job: status, progression and per-finding outcomes.
 * Findings are referenced by id and type only; no sensitive value ever appears here.
 */
public record ObfuscationJobStatusDto(
        String jobId,
        String status,
        int processed,
        int total,
        List<OutcomeDto> outcomes
) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutcomeDto(String findingId, String piiType, String outcome, String reason) {
    }
}
