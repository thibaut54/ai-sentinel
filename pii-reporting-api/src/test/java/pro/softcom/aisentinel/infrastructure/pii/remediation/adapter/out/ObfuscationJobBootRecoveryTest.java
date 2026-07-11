package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.remediation.port.out.ObfuscationJobStore;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ObfuscationJobBootRecovery")
class ObfuscationJobBootRecoveryTest {

    @Mock
    private ObfuscationJobStore obfuscationJobStore;

    @InjectMocks
    private ObfuscationJobBootRecovery bootRecovery;

    @Test
    @DisplayName("Should_MarkRunningJobsInterrupted_When_ApplicationBoots")
    void Should_MarkRunningJobsInterrupted_When_ApplicationBoots() {
        when(obfuscationJobStore.markInterruptedOnBoot()).thenReturn(2);

        bootRecovery.markInterruptedJobs();

        verify(obfuscationJobStore).markInterruptedOnBoot();
    }

    @Test
    @DisplayName("Should_StaySilent_When_NoRunningJobFoundAtBoot")
    void Should_StaySilent_When_NoRunningJobFoundAtBoot() {
        when(obfuscationJobStore.markInterruptedOnBoot()).thenReturn(0);

        bootRecovery.markInterruptedJobs();

        verify(obfuscationJobStore).markInterruptedOnBoot();
    }
}
