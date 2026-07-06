package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.application.pii.remediation.port.in.ExecuteObfuscationPort.ObfuscationSubmission;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRedactionOutcome;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJob;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJobStatus;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationPlan;
import pro.softcom.aisentinel.domain.pii.remediation.RedactionOutcome;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobRequestDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobStatusDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationPlanDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationScopeDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationSelectionDto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("RemediationJobDtoMapper")
class RemediationJobDtoMapperTest {

    private final RemediationJobDtoMapper mapper = new RemediationJobDtoMapper();

    @Nested
    @DisplayName("toSelection")
    class ToSelection {

        @Test
        @DisplayName("Should_MapScopeAndCriteria_When_SelectionDtoIsComplete")
        void Should_MapScopeAndCriteria_When_SelectionDtoIsComplete() {
            RemediationSelectionDto dto = new RemediationSelectionDto(
                    new RemediationScopeDto("SPACE", "p1", null),
                    List.of("EMAIL"),
                    List.of("high"),
                    List.of("excluded-1"),
                    List.of("included-1"));

            RemediationSelection selection = mapper.toSelection(dto);

            assertSoftly(softly -> {
                softly.assertThat(selection.spaceKey()).isEqualTo("SPACE");
                softly.assertThat(selection.pageId()).isEqualTo("p1");
                softly.assertThat(selection.attachmentName()).isNull();
                softly.assertThat(selection.piiTypes()).containsExactly("EMAIL");
                softly.assertThat(selection.severities())
                        .containsExactly(PersonallyIdentifiableInformationSeverity.HIGH);
                softly.assertThat(selection.excludedFindingIds()).containsExactly("excluded-1");
                softly.assertThat(selection.includedFindingIds()).containsExactly("included-1");
            });
        }

        @Test
        @DisplayName("Should_RejectSelection_When_ScopeIsMissing")
        void Should_RejectSelection_When_ScopeIsMissing() {
            RemediationSelectionDto dto = new RemediationSelectionDto(null, null, null, null, null);

            assertThatThrownBy(() -> mapper.toSelection(dto))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scope");
        }
    }

    @Nested
    @DisplayName("toSubmission")
    class ToSubmission {

        @Test
        @DisplayName("Should_CarryChecksumAndActor_When_RequestIsValid")
        void Should_CarryChecksumAndActor_When_RequestIsValid() {
            ObfuscationJobRequestDto request = new ObfuscationJobRequestDto(
                    new RemediationSelectionDto(new RemediationScopeDto("SPACE", null, null),
                            List.of("EMAIL"), null, null, null),
                    "checksum-1");

            ObfuscationSubmission submission = mapper.toSubmission(request, "officer");

            assertSoftly(softly -> {
                softly.assertThat(submission.selection().spaceKey()).isEqualTo("SPACE");
                softly.assertThat(submission.selectionChecksum()).isEqualTo("checksum-1");
                softly.assertThat(submission.actor()).isEqualTo("officer");
            });
        }

        @Test
        @DisplayName("Should_RejectSubmission_When_ChecksumIsMissing")
        void Should_RejectSubmission_When_ChecksumIsMissing() {
            ObfuscationJobRequestDto request = new ObfuscationJobRequestDto(
                    new RemediationSelectionDto(new RemediationScopeDto("SPACE", null, null),
                            null, null, null, null),
                    " ");

            assertThatThrownBy(() -> mapper.toSubmission(request, "officer"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("selectionChecksum");
        }
    }

    @Nested
    @DisplayName("toDto")
    class ToDto {

        @Test
        @DisplayName("Should_MapSeverityKeysToNames_When_MappingPlan")
        void Should_MapSeverityKeysToNames_When_MappingPlan() {
            ObfuscationPlan plan = ObfuscationPlan.builder()
                    .totalFindings(3)
                    .bySeverity(Map.of(PersonallyIdentifiableInformationSeverity.HIGH, 1,
                            PersonallyIdentifiableInformationSeverity.MEDIUM, 2))
                    .pagesImpacted(2)
                    .falsePositivesReported(1)
                    .attachmentExclusions(1)
                    .selectionChecksum("checksum-1")
                    .build();

            ObfuscationPlanDto dto = mapper.toDto(plan);

            assertSoftly(softly -> {
                softly.assertThat(dto.totalFindings()).isEqualTo(3);
                softly.assertThat(dto.bySeverity()).containsOnly(
                        Map.entry("HIGH", 1), Map.entry("MEDIUM", 2));
                softly.assertThat(dto.pagesImpacted()).isEqualTo(2);
                softly.assertThat(dto.falsePositivesReported()).isEqualTo(1);
                softly.assertThat(dto.attachmentExclusions()).isEqualTo(1);
                softly.assertThat(dto.selectionChecksum()).isEqualTo("checksum-1");
            });
        }

        @Test
        @DisplayName("Should_SortOutcomesByFindingId_When_MappingJob")
        void Should_SortOutcomesByFindingId_When_MappingJob() {
            ObfuscationJob job = ObfuscationJob.builder()
                    .id("job-1")
                    .spaceKey("SPACE")
                    .status(ObfuscationJobStatus.COMPLETED_WITH_ERRORS)
                    .submittedSelection(RemediationSelection.builder().spaceKey("SPACE").build())
                    .processed(2)
                    .total(2)
                    .outcomes(Map.of(
                            "finding-b", new FindingRedactionOutcome("EMAIL",
                                    RedactionOutcome.SKIPPED_VALUE_NOT_FOUND, "value not found"),
                            "finding-a", FindingRedactionOutcome.of("IBAN", RedactionOutcome.REDACTED)))
                    .actor("officer")
                    .createdAt(Instant.parse("2026-07-06T10:00:00Z"))
                    .updatedAt(Instant.parse("2026-07-06T10:05:00Z"))
                    .build();

            ObfuscationJobStatusDto dto = mapper.toDto(job);

            assertSoftly(softly -> {
                softly.assertThat(dto.jobId()).isEqualTo("job-1");
                softly.assertThat(dto.status()).isEqualTo("COMPLETED_WITH_ERRORS");
                softly.assertThat(dto.processed()).isEqualTo(2);
                softly.assertThat(dto.total()).isEqualTo(2);
                softly.assertThat(dto.outcomes()).containsExactly(
                        new ObfuscationJobStatusDto.OutcomeDto("finding-a", "IBAN", "REDACTED", null),
                        new ObfuscationJobStatusDto.OutcomeDto("finding-b", "EMAIL",
                                "SKIPPED_VALUE_NOT_FOUND", "value not found"));
            });
        }
    }
}
