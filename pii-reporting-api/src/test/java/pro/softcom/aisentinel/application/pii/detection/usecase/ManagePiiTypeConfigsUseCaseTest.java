package pro.softcom.aisentinel.application.pii.detection.usecase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiTypeConfigsPort;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiTypeConfigsPort.CreatePiiTypeConfigCommand;
import pro.softcom.aisentinel.application.pii.detection.port.out.PiiTypeConfigRepository;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManagePiiTypeConfigsUseCase")
class ManagePiiTypeConfigsUseCaseTest {

    @Mock
    private PiiTypeConfigRepository repository;

    @InjectMocks
    private ManagePiiTypeConfigsUseCase useCase;

    @Test
    @DisplayName("Should_CreateConfig_When_ValidInputProvided")
    void Should_CreateConfig_When_ValidInputProvided() {
        // Arrange
        when(repository.findByPiiTypeAndDetector("CUSTOM_LABEL", "GLINER")).thenReturn(Optional.empty());
        PiiTypeConfig saved = PiiTypeConfig.builder()
                .id(1L)
                .piiType("CUSTOM_LABEL")
                .detector("GLINER")
                .enabled(true)
                .threshold(0.5)
                .category("Custom")
                .detectorLabel("custom label")
                .custom(true)
                .updatedBy("admin")
                .build();
        when(repository.save(any(PiiTypeConfig.class))).thenReturn(saved);

        // Act
        PiiTypeConfig result = useCase.createConfig(new CreatePiiTypeConfigCommand(
                "CUSTOM_LABEL", "GLINER", true, 0.5,
                "Custom", "custom label", null, true, null, "HIGH", "admin"
        ));

        // Assert
        assertThat(result.getPiiType()).isEqualTo("CUSTOM_LABEL");
        assertThat(result.isCustom()).isTrue();
        assertThat(result.getDetectorLabel()).isEqualTo("custom label");

        ArgumentCaptor<PiiTypeConfig> captor = ArgumentCaptor.forClass(PiiTypeConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isCustom()).isTrue();
        assertThat(captor.getValue().getCategory()).isEqualTo("Custom");
    }

    @Test
    @DisplayName("Should_ThrowException_When_DuplicatePiiTypeAndDetector")
    void Should_ThrowException_When_DuplicatePiiTypeAndDetector() {
        // Arrange
        PiiTypeConfig existing = PiiTypeConfig.builder()
                .piiType("EXISTING_TYPE")
                .detector("GLINER")
                .build();
        when(repository.findByPiiTypeAndDetector("EXISTING_TYPE", "GLINER")).thenReturn(Optional.of(existing));

        // Act & Assert
        var command = new CreatePiiTypeConfigCommand(
                "EXISTING_TYPE", "GLINER", true, 0.5,
                "Custom", "existing type", null, true, null, null, "admin"
        );
        assertThatThrownBy(() -> useCase.createConfig(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Should_ThrowException_When_InvalidPiiTypeFormat")
    void Should_ThrowException_When_InvalidPiiTypeFormat() {
        var command = new CreatePiiTypeConfigCommand(
                "invalid-type", "GLINER", true, 0.5,
                "Custom", "label", null, true, null, null, "admin"
        );
        assertThatThrownBy(() -> useCase.createConfig(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UPPER_SNAKE_CASE");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Should_ThrowException_When_InvalidDetector")
    void Should_ThrowException_When_InvalidDetector() {
        var command = new CreatePiiTypeConfigCommand(
                "CUSTOM_LABEL", "INVALID", true, 0.5,
                "Custom", "label", null, true, null, null, "admin"
        );
        assertThatThrownBy(() -> useCase.createConfig(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GLINER, PRESIDIO, REGEX");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Should_ThrowException_When_GlinerDetectorLabelBlank")
    void Should_ThrowException_When_GlinerDetectorLabelBlank() {
        var command = new CreatePiiTypeConfigCommand(
                "CUSTOM_LABEL", "GLINER", true, 0.5,
                "Custom", "", null, true, null, null, "admin"
        );
        assertThatThrownBy(() -> useCase.createConfig(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Detector label is required");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Should_ThrowException_When_ThresholdOutOfRange")
    void Should_ThrowException_When_ThresholdOutOfRange() {
        var command = new CreatePiiTypeConfigCommand(
                "CUSTOM_LABEL", "GLINER", true, 1.5,
                "Custom", "label", null, true, null, null, "admin"
        );
        assertThatThrownBy(() -> useCase.createConfig(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Threshold");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Should_AllowPresidioWithoutDetectorLabel_When_DetectorIsPresidio")
    void Should_AllowPresidioWithoutDetectorLabel_When_DetectorIsPresidio() {
        // Arrange
        when(repository.findByPiiTypeAndDetector("CUSTOM_LABEL", "PRESIDIO")).thenReturn(Optional.empty());
        PiiTypeConfig saved = PiiTypeConfig.builder()
                .id(2L)
                .piiType("CUSTOM_LABEL")
                .detector("PRESIDIO")
                .enabled(true)
                .threshold(0.7)
                .category("Custom")
                .custom(true)
                .updatedBy("admin")
                .build();
        when(repository.save(any(PiiTypeConfig.class))).thenReturn(saved);

        // Act - no detectorLabel for PRESIDIO should be fine
        PiiTypeConfig result = useCase.createConfig(new CreatePiiTypeConfigCommand(
                "CUSTOM_LABEL", "PRESIDIO", true, 0.7,
                "Custom", null, null, true, null, null, "admin"
        ));

        // Assert
        assertThat(result).isNotNull();
        verify(repository).save(any());
    }

    // ====== deleteConfig tests ======

    @Test
    @DisplayName("Should_DeleteConfig_When_CustomType")
    void Should_DeleteConfig_When_CustomType() {
        // Arrange
        PiiTypeConfig customConfig = PiiTypeConfig.builder()
                .piiType("CUSTOM_LABEL")
                .detector("GLINER")
                .custom(true)
                .build();
        when(repository.findByPiiTypeAndDetector("CUSTOM_LABEL", "GLINER")).thenReturn(Optional.of(customConfig));

        // Act
        useCase.deleteConfig("CUSTOM_LABEL", "GLINER");

        // Assert
        verify(repository).deleteByPiiTypeAndDetector("CUSTOM_LABEL", "GLINER");
    }

    @Test
    @DisplayName("Should_ThrowIllegalState_When_DeletingSystemType")
    void Should_ThrowIllegalState_When_DeletingSystemType() {
        // Arrange
        PiiTypeConfig systemConfig = PiiTypeConfig.builder()
                .piiType("EMAIL")
                .detector("GLINER")
                .custom(false)
                .build();
        when(repository.findByPiiTypeAndDetector("EMAIL", "GLINER")).thenReturn(Optional.of(systemConfig));

        // Act & Assert
        assertThatThrownBy(() -> useCase.deleteConfig("EMAIL", "GLINER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot delete system-defined");

        verify(repository, never()).deleteByPiiTypeAndDetector(any(), any());
    }

    @Test
    @DisplayName("Should_ThrowException_When_ConfigNotFoundForDelete")
    void Should_ThrowException_When_ConfigNotFoundForDelete() {
        // Arrange
        when(repository.findByPiiTypeAndDetector("NONEXISTENT", "GLINER")).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> useCase.deleteConfig("NONEXISTENT", "GLINER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");

        verify(repository, never()).deleteByPiiTypeAndDetector(any(), any());
    }

    // ====== GLINER2 detector_description tests (spec §5.1 / §9) ======

    @Test
    @DisplayName("Should_AcceptGliner2Detector_When_Validating")
    void Should_AcceptGliner2Detector_When_Validating() {
        when(repository.updateAtomically("EMAIL", "GLINER2", true, 0.5, "desc", null, "admin"))
                .thenReturn(PiiTypeConfig.builder()
                        .piiType("EMAIL").detector("GLINER2").enabled(true).threshold(0.5)
                        .detectorDescription("desc").build());

        PiiTypeConfig result = useCase.updateConfig("EMAIL", "GLINER2", true, 0.5, "desc", null, "admin");

        assertThat(result.getDetectorDescription()).isEqualTo("desc");
        verify(repository).updateAtomically("EMAIL", "GLINER2", true, 0.5, "desc", null, "admin");
    }

    @Test
    @DisplayName("Should_ForwardDescription_When_BulkUpdate")
    void Should_ForwardDescription_When_BulkUpdate() {
        var update = new ManagePiiTypeConfigsPort.PiiTypeConfigUpdate(
                "EMAIL", "GLINER2", true, 0.5, "adresse e-mail", null);
        when(repository.bulkUpdateAtomically(java.util.List.of(update), "admin"))
                .thenReturn(java.util.List.of(PiiTypeConfig.builder()
                        .piiType("EMAIL").detector("GLINER2").enabled(true).threshold(0.5)
                        .detectorDescription("adresse e-mail").build()));

        var result = useCase.bulkUpdate(java.util.List.of(update), "admin");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getDetectorDescription()).isEqualTo("adresse e-mail");
        // The update (with its description) is forwarded verbatim to the repository,
        // which applies "absent = unchanged" semantics when description is null.
        verify(repository).bulkUpdateAtomically(java.util.List.of(update), "admin");
    }

    @Test
    @DisplayName("Should_RejectUnknownDetector_When_Validating")
    void Should_RejectUnknownDetector_When_Validating() {
        assertThatThrownBy(() -> useCase.updateConfig("EMAIL", "UNKNOWN", true, 0.5, null, null, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GLINER2");
    }

    // ====== per-type llmJudgeEnabled tests ======

    @Test
    @DisplayName("Should_ForwardLlmJudgeFlag_When_UpdateConfig")
    void Should_ForwardLlmJudgeFlag_When_UpdateConfig() {
        when(repository.updateAtomically("EMAIL", "GLINER2", true, 0.5, null, false, "admin"))
                .thenReturn(PiiTypeConfig.builder()
                        .piiType("EMAIL").detector("GLINER2").enabled(true).threshold(0.5)
                        .llmJudgeEnabled(false).build());

        PiiTypeConfig result = useCase.updateConfig("EMAIL", "GLINER2", true, 0.5, null, false, "admin");

        assertThat(result.isLlmJudgeEnabled()).isFalse();
        verify(repository).updateAtomically("EMAIL", "GLINER2", true, 0.5, null, false, "admin");
    }

    @Test
    @DisplayName("Should_ForwardNullLlmJudgeFlag_When_UpdateConfigOmitsIt")
    void Should_ForwardNullLlmJudgeFlag_When_UpdateConfigOmitsIt() {
        when(repository.updateAtomically("EMAIL", "GLINER2", true, 0.5, null, null, "admin"))
                .thenReturn(PiiTypeConfig.builder()
                        .piiType("EMAIL").detector("GLINER2").enabled(true).threshold(0.5).build());

        useCase.updateConfig("EMAIL", "GLINER2", true, 0.5, null, null, "admin");

        verify(repository).updateAtomically("EMAIL", "GLINER2", true, 0.5, null, null, "admin");
    }

    @Test
    @DisplayName("Should_DefaultLlmJudgeToTrue_When_CreateConfigWithTrue")
    void Should_DefaultLlmJudgeToTrue_When_CreateConfigWithTrue() {
        when(repository.findByPiiTypeAndDetector("CUSTOM_LABEL", "PRESIDIO")).thenReturn(Optional.empty());
        when(repository.save(any(PiiTypeConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        useCase.createConfig(new CreatePiiTypeConfigCommand(
                "CUSTOM_LABEL", "PRESIDIO", true, 0.7,
                "Custom", null, null, true, null, null, "admin"
        ));

        ArgumentCaptor<PiiTypeConfig> captor = ArgumentCaptor.forClass(PiiTypeConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isLlmJudgeEnabled()).isTrue();
    }

    @Test
    @DisplayName("Should_PersistLlmJudgeFalse_When_CreateConfigWithFalse")
    void Should_PersistLlmJudgeFalse_When_CreateConfigWithFalse() {
        when(repository.findByPiiTypeAndDetector("CUSTOM_LABEL", "PRESIDIO")).thenReturn(Optional.empty());
        when(repository.save(any(PiiTypeConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        useCase.createConfig(new CreatePiiTypeConfigCommand(
                "CUSTOM_LABEL", "PRESIDIO", true, 0.7,
                "Custom", null, null, false, null, null, "admin"
        ));

        ArgumentCaptor<PiiTypeConfig> captor = ArgumentCaptor.forClass(PiiTypeConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isLlmJudgeEnabled()).isFalse();
    }
}
