package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.mapper;

import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.remediation.port.in.ExecuteObfuscationPort.ObfuscationSubmission;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRedactionOutcome;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJob;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationPlan;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobRequestDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobStatusDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobStatusDto.OutcomeDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationPlanDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationScopeDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationSelectionDto;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Maps plan/job REST DTOs to application port models and back. Invalid enum-like
 * inputs raise {@link IllegalArgumentException}, mapped to 400 by the global handler.
 */
@Component
public class RemediationJobDtoMapper {

    public RemediationSelection toSelection(RemediationSelectionDto dto) {
        if (dto == null || dto.scope() == null) {
            throw new IllegalArgumentException("selection scope is required");
        }
        RemediationScopeDto scope = dto.scope();
        return RemediationSelection.builder()
                .spaceKey(scope.spaceKey())
                .pageId(scope.pageId())
                .attachmentName(scope.attachmentName())
                .piiTypes(dto.piiTypes() == null ? List.of() : dto.piiTypes())
                .severities(parseSeverities(dto.severities()))
                .excludedFindingIds(toSet(dto.excludedFindingIds()))
                .includedFindingIds(toSet(dto.includedFindingIds()))
                .build();
    }

    public ObfuscationSubmission toSubmission(ObfuscationJobRequestDto request, String actor) {
        if (request == null || request.selectionChecksum() == null || request.selectionChecksum().isBlank()) {
            throw new IllegalArgumentException("selectionChecksum is required");
        }
        return new ObfuscationSubmission(toSelection(request.selection()), request.selectionChecksum(), actor);
    }

    public ObfuscationPlanDto toDto(ObfuscationPlan plan) {
        Map<String, Integer> bySeverity = plan.bySeverity().entrySet().stream()
                .collect(Collectors.toMap(entry -> entry.getKey().name(), Map.Entry::getValue));
        return new ObfuscationPlanDto(plan.totalFindings(), bySeverity, plan.pagesImpacted(),
                plan.falsePositivesReported(), plan.attachmentExclusions(), plan.selectionChecksum());
    }

    public ObfuscationJobStatusDto toDto(ObfuscationJob job) {
        List<OutcomeDto> outcomes = job.outcomes().entrySet().stream()
                .map(RemediationJobDtoMapper::toOutcomeDto)
                .sorted(Comparator.comparing(OutcomeDto::findingId))
                .toList();
        return new ObfuscationJobStatusDto(job.id(), job.status().name(),
                job.processed(), job.total(), outcomes);
    }

    private static OutcomeDto toOutcomeDto(Map.Entry<String, FindingRedactionOutcome> entry) {
        FindingRedactionOutcome outcome = entry.getValue();
        return new OutcomeDto(entry.getKey(), outcome.piiType(), outcome.outcome().name(), outcome.reason());
    }

    private static List<PersonallyIdentifiableInformationSeverity> parseSeverities(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .map(severity -> PersonallyIdentifiableInformationSeverity.valueOf(
                        severity.toUpperCase(Locale.ROOT)))
                .toList();
    }

    private static Set<String> toSet(List<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }
}
