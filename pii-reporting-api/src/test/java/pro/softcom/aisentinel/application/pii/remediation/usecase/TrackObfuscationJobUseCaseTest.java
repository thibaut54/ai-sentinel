package pro.softcom.aisentinel.application.pii.remediation.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.remediation.port.out.ObfuscationJobStore;
import pro.softcom.aisentinel.application.pii.remediation.port.out.RemediationConfigPort;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJob;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJobStatus;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationDisabledException;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TrackObfuscationJobUseCase")
class TrackObfuscationJobUseCaseTest {

    @Mock
    private RemediationConfigPort remediationConfigPort;

    @Mock
    private ObfuscationJobStore obfuscationJobStore;

    private TrackObfuscationJobUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new TrackObfuscationJobUseCase(remediationConfigPort, obfuscationJobStore);
        lenient().when(remediationConfigPort.isRemediationEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("Should_ThrowRemediationDisabled_When_FeatureFlagIsOff")
    void Should_ThrowRemediationDisabled_When_FeatureFlagIsOff() {
        when(remediationConfigPort.isRemediationEnabled()).thenReturn(false);

        assertThatThrownBy(() -> useCase.findJob("job-1"))
                .isInstanceOf(RemediationDisabledException.class);
    }

    @Test
    @DisplayName("Should_ReturnJob_When_JobExists")
    void Should_ReturnJob_When_JobExists() {
        ObfuscationJob job = ObfuscationJob.builder()
                .id("job-1")
                .spaceKey("SPACE")
                .status(ObfuscationJobStatus.COMPLETED)
                .submittedSelection(RemediationSelection.builder().spaceKey("SPACE").build())
                .actor("officer")
                .createdAt(Instant.parse("2026-07-06T10:00:00Z"))
                .updatedAt(Instant.parse("2026-07-06T10:05:00Z"))
                .build();
        when(obfuscationJobStore.findById("job-1")).thenReturn(Optional.of(job));

        assertThat(useCase.findJob("job-1")).contains(job);
    }

    @Test
    @DisplayName("Should_ReturnEmpty_When_JobUnknown")
    void Should_ReturnEmpty_When_JobUnknown() {
        when(obfuscationJobStore.findById("unknown")).thenReturn(Optional.empty());

        assertThat(useCase.findJob("unknown")).isEmpty();
    }
}
