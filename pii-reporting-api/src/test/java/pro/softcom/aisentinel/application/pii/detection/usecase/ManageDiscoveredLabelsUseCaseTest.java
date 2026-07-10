package pro.softcom.aisentinel.application.pii.detection.usecase;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManageDiscoveredLabelsPort.PromoteDiscoveredLabelCommand;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiTypeConfigsPort;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiTypeConfigsPort.CreatePiiTypeConfigCommand;
import pro.softcom.aisentinel.application.pii.detection.port.out.DiscoveredLabelStore;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabel;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabelStatus;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManageDiscoveredLabelsUseCase")
class ManageDiscoveredLabelsUseCaseTest {

    @Mock
    private ManagePiiTypeConfigsPort managePiiTypeConfigsPort;

    @Mock
    private DiscoveredLabelStore store;

    private ManageDiscoveredLabelsUseCase useCase;

    private ManageDiscoveredLabelsUseCase newUseCase() {
        return new ManageDiscoveredLabelsUseCase(managePiiTypeConfigsPort, store);
    }

    @Test
    @DisplayName("Should_CreateMinistralConfigThenMarkPromoted_When_Promote")
    void Should_CreateMinistralConfigThenMarkPromoted_When_Promote() {
        // Arrange
        useCase = newUseCase();
        PiiTypeConfig created = PiiTypeConfig.builder()
                .piiType("VEHICLE_COLOR").detector("MINISTRAL").enabled(true).threshold(0.7).build();
        when(managePiiTypeConfigsPort.createConfig(any())).thenReturn(created);
        var command = new PromoteDiscoveredLabelCommand(
                "VEHICLE_COLOR", "OTHER", "LOW", 0.7, "vehicle color", "CH", "admin");

        // Act
        PiiTypeConfig result = useCase.promote(command);

        // Assert
        ArgumentCaptor<CreatePiiTypeConfigCommand> captor = ArgumentCaptor.forClass(CreatePiiTypeConfigCommand.class);
        verify(managePiiTypeConfigsPort).createConfig(captor.capture());
        CreatePiiTypeConfigCommand createCommand = captor.getValue();
        assertSoftly(softly -> {
            softly.assertThat(result).isSameAs(created);
            softly.assertThat(createCommand.piiType()).isEqualTo("VEHICLE_COLOR");
            softly.assertThat(createCommand.detector()).isEqualTo("MINISTRAL");
            softly.assertThat(createCommand.enabled()).isTrue();
            softly.assertThat(createCommand.threshold()).isEqualTo(0.7);
            softly.assertThat(createCommand.category()).isEqualTo("OTHER");
            softly.assertThat(createCommand.detectorLabel()).isEqualTo("vehicle color");
            softly.assertThat(createCommand.countryCode()).isEqualTo("CH");
            softly.assertThat(createCommand.severity()).isEqualTo("LOW");
            softly.assertThat(createCommand.createdBy()).isEqualTo("admin");
        });

        InOrder inOrder = inOrder(managePiiTypeConfigsPort, store);
        inOrder.verify(managePiiTypeConfigsPort).createConfig(any());
        inOrder.verify(store).markPromoted("VEHICLE_COLOR");
    }

    @Test
    @DisplayName("Should_DefaultDetectorLabelToLowerCasedLabel_When_DetectorLabelBlank")
    void Should_DefaultDetectorLabelToLowerCasedLabel_When_DetectorLabelBlank() {
        // Arrange
        useCase = newUseCase();
        when(managePiiTypeConfigsPort.createConfig(any()))
                .thenReturn(PiiTypeConfig.builder().piiType("PET_NAME").detector("MINISTRAL").build());
        var command = new PromoteDiscoveredLabelCommand(
                "PET_NAME", "OTHER", null, 0.5, "  ", null, "admin");

        // Act
        useCase.promote(command);

        // Assert
        ArgumentCaptor<CreatePiiTypeConfigCommand> captor = ArgumentCaptor.forClass(CreatePiiTypeConfigCommand.class);
        verify(managePiiTypeConfigsPort).createConfig(captor.capture());
        assertThat(captor.getValue().detectorLabel()).isEqualTo("pet_name");
    }

    @Test
    @DisplayName("Should_NotMarkPromoted_When_ConfigCreationFails")
    void Should_NotMarkPromoted_When_ConfigCreationFails() {
        // Arrange
        useCase = newUseCase();
        when(managePiiTypeConfigsPort.createConfig(any()))
                .thenThrow(new IllegalArgumentException("already exists"));
        var command = new PromoteDiscoveredLabelCommand(
                "DUP_LABEL", "OTHER", "LOW", 0.5, "dup label", null, "admin");

        // Act & Assert
        assertThatThrownBy(() -> useCase.promote(command))
                .isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).markPromoted(any());
    }

    @Test
    @DisplayName("Should_MarkIgnored_When_Ignore")
    void Should_MarkIgnored_When_Ignore() {
        useCase = newUseCase();

        useCase.ignore("MISC_LABEL");

        verify(store).markIgnored("MISC_LABEL");
    }

    @Test
    @DisplayName("Should_ReturnPendingLabels_When_ListPending")
    void Should_ReturnPendingLabels_When_ListPending() {
        // Arrange
        useCase = newUseCase();
        DiscoveredLabel pending = new DiscoveredLabel(
                "VEHICLE_COLOR", 3L, LocalDateTime.now(), LocalDateTime.now(), DiscoveredLabelStatus.PENDING);
        when(store.findByStatus(DiscoveredLabelStatus.PENDING)).thenReturn(List.of(pending));

        // Act
        List<DiscoveredLabel> result = useCase.listPending();

        // Assert
        assertThat(result).containsExactly(pending);
        verify(store).findByStatus(DiscoveredLabelStatus.PENDING);
    }
}
