package pro.softcom.aisentinel.infrastructure.pii.export.adapter.in;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.export.port.in.ExportDetectionReportPort;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.remediation.SpaceFalsePositivesChanged;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("Space false positives changed listener tests")
class SpaceFalsePositivesChangedListenerTest {

    @Mock
    private ExportDetectionReportPort exportDetectionReportPort;

    @InjectMocks
    private SpaceFalsePositivesChangedListener listener;

    @Test
    @DisplayName("Should_RegenerateReport_When_FalsePositivesChanged")
    void Should_RegenerateReport_When_FalsePositivesChanged() {
        // Given
        SpaceFalsePositivesChanged event = new SpaceFalsePositivesChanged("scan-123", "TEST-KEY");

        // When
        listener.onSpaceFalsePositivesChanged(event);

        // Then
        verify(exportDetectionReportPort).export("scan-123", SourceType.CONFLUENCE, "TEST-KEY");
    }

    @Test
    @DisplayName("Should_NotThrowException_When_NullEventReceived")
    void Should_NotThrowException_When_NullEventReceived() {
        // Given (null event)

        // When
        listener.onSpaceFalsePositivesChanged(null);

        // Then
        verifyNoInteractions(exportDetectionReportPort);
    }

    @Test
    @DisplayName("Should_CatchException_When_RegenerationFails")
    void Should_CatchException_When_RegenerationFails() {
        // Given
        SpaceFalsePositivesChanged event = new SpaceFalsePositivesChanged("scan-456", "FAIL-KEY");
        doThrow(new RuntimeException("Export failed"))
                .when(exportDetectionReportPort)
                .export("scan-456", SourceType.CONFLUENCE, "FAIL-KEY");

        // When
        listener.onSpaceFalsePositivesChanged(event);

        // Then
        verify(exportDetectionReportPort).export("scan-456", SourceType.CONFLUENCE, "FAIL-KEY");
    }
}
