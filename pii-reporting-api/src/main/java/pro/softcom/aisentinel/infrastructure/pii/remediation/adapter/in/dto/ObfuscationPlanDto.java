package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

import java.util.Map;

/**
 * Backend-computed preview of an obfuscation run, displayed as-is by the confirmation
 * dialog. The checksum must be sent back on job submission.
 */
public record ObfuscationPlanDto(
        int totalFindings,
        Map<String, Integer> bySeverity,
        int pagesImpacted,
        int falsePositivesReported,
        int attachmentExclusions,
        String selectionChecksum
) {
}
