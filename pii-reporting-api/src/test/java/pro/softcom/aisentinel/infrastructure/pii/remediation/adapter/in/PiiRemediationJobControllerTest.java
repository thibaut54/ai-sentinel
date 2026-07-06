package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import pro.softcom.aisentinel.application.pii.remediation.port.in.ExecuteObfuscationPort;
import pro.softcom.aisentinel.application.pii.remediation.port.in.ExecuteObfuscationPort.ObfuscationSubmission;
import pro.softcom.aisentinel.application.pii.remediation.port.in.PlanObfuscationPort;
import pro.softcom.aisentinel.application.pii.remediation.port.in.TrackObfuscationJobPort;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJob;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJobStatus;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationPlan;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;
import pro.softcom.aisentinel.domain.pii.remediation.SelectionOutdatedException;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobCreatedDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobRequestDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationJobStatusDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.ObfuscationPlanDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationScopeDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto.RemediationSelectionDto;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.mapper.RemediationJobDtoMapper;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PiiRemediationJobController")
class PiiRemediationJobControllerTest {

    @Mock
    private PlanObfuscationPort planObfuscationPort;

    @Mock
    private ExecuteObfuscationPort executeObfuscationPort;

    @Mock
    private TrackObfuscationJobPort trackObfuscationJobPort;

    private PiiRemediationJobController controller;

    @BeforeEach
    void setUp() {
        controller = new PiiRemediationJobController(planObfuscationPort, executeObfuscationPort,
                trackObfuscationJobPort, new RemediationJobDtoMapper());
    }

    @Nested
    @DisplayName("POST /plan")
    class Plan {

        @Test
        @DisplayName("Should_ReturnBackendComputedPlan_When_SelectionSubmitted")
        void Should_ReturnBackendComputedPlan_When_SelectionSubmitted() {
            when(planObfuscationPort.plan(any(RemediationSelection.class)))
                    .thenReturn(ObfuscationPlan.builder()
                            .totalFindings(2)
                            .bySeverity(Map.of())
                            .pagesImpacted(1)
                            .falsePositivesReported(0)
                            .attachmentExclusions(1)
                            .selectionChecksum("checksum-1")
                            .build());

            ResponseEntity<ObfuscationPlanDto> response = controller.plan(selectionDto());

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                softly.assertThat(response.getBody().totalFindings()).isEqualTo(2);
                softly.assertThat(response.getBody().selectionChecksum()).isEqualTo("checksum-1");
            });
        }
    }

    @Nested
    @DisplayName("POST /jobs")
    class SubmitJob {

        @Test
        @DisplayName("Should_ReturnAcceptedWithJobId_When_SubmissionSucceeds")
        void Should_ReturnAcceptedWithJobId_When_SubmissionSucceeds() {
            when(executeObfuscationPort.execute(any(ObfuscationSubmission.class))).thenReturn("job-1");

            ResponseEntity<ObfuscationJobCreatedDto> response =
                    controller.submitJob(jobRequest(), () -> "officer");

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
                softly.assertThat(response.getBody().jobId()).isEqualTo("job-1");
            });
        }

        @Test
        @DisplayName("Should_FallBackToSystemActor_When_NoPrincipal")
        void Should_FallBackToSystemActor_When_NoPrincipal() {
            when(executeObfuscationPort.execute(any(ObfuscationSubmission.class))).thenReturn("job-1");

            controller.submitJob(jobRequest(), null);

            verify(executeObfuscationPort).execute(
                    argThat(submission -> "system".equals(submission.actor())));
        }

        @Test
        @DisplayName("Should_PropagateSelectionOutdated_When_ChecksumDiverged")
        void Should_PropagateSelectionOutdated_When_ChecksumDiverged() {
            when(executeObfuscationPort.execute(any(ObfuscationSubmission.class)))
                    .thenThrow(new SelectionOutdatedException("selection changed"));
            ObfuscationJobRequestDto request = jobRequest();
            Principal principal = () -> "officer";

            assertThatThrownBy(() -> controller.submitJob(request, principal))
                    .isInstanceOf(SelectionOutdatedException.class);
        }
    }

    @Nested
    @DisplayName("GET /jobs/{id}")
    class GetJob {

        @Test
        @DisplayName("Should_ReturnJobStatus_When_JobExists")
        void Should_ReturnJobStatus_When_JobExists() {
            when(trackObfuscationJobPort.findJob("job-1")).thenReturn(Optional.of(ObfuscationJob.builder()
                    .id("job-1")
                    .spaceKey("SPACE")
                    .status(ObfuscationJobStatus.RUNNING)
                    .submittedSelection(RemediationSelection.builder().spaceKey("SPACE").build())
                    .processed(1)
                    .total(3)
                    .actor("officer")
                    .createdAt(Instant.parse("2026-07-06T10:00:00Z"))
                    .updatedAt(Instant.parse("2026-07-06T10:01:00Z"))
                    .build()));

            ResponseEntity<ObfuscationJobStatusDto> response = controller.getJob("job-1");

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                softly.assertThat(response.getBody().status()).isEqualTo("RUNNING");
                softly.assertThat(response.getBody().processed()).isEqualTo(1);
                softly.assertThat(response.getBody().total()).isEqualTo(3);
            });
        }

        @Test
        @DisplayName("Should_ReturnNotFound_When_JobUnknown")
        void Should_ReturnNotFound_When_JobUnknown() {
            when(trackObfuscationJobPort.findJob("unknown")).thenReturn(Optional.empty());

            ResponseEntity<ObfuscationJobStatusDto> response = controller.getJob("unknown");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    private static RemediationSelectionDto selectionDto() {
        return new RemediationSelectionDto(new RemediationScopeDto("SPACE", null, null),
                List.of("EMAIL"), null, null, null);
    }

    private static ObfuscationJobRequestDto jobRequest() {
        return new ObfuscationJobRequestDto(selectionDto(), "checksum-1");
    }
}
