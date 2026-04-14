package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiDetectionConfigPort;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiDetectionConfigPort.UpdatePiiDetectionConfigCommand;
import pro.softcom.aisentinel.domain.pii.detection.PiiDetectionConfig;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto.PiiDetectionConfigResponseDto;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto.UpdatePiiDetectionConfigRequestDto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PiiDetectionConfigController")
class PiiDetectionConfigControllerTest {

    @Mock
    private ManagePiiDetectionConfigPort managePiiDetectionConfigPort;

    @InjectMocks
    private PiiDetectionConfigController controller;

    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 3, 15, 10, 30, 0);

    private PiiDetectionConfig buildConfig() {
        return new PiiDetectionConfig(
                1,
                true,
                true,
                false,
                new BigDecimal("0.75"),
                3,
                false,
                UPDATED_AT,
                "admin"
        );
    }

    @Nested
    @DisplayName("getConfig")
    class GetConfig {

        @Test
        @DisplayName("Should_Return200WithConfig_When_ConfigRetrievedSuccessfully")
        void Should_Return200WithConfig_When_ConfigRetrievedSuccessfully() {
            // Arrange
            PiiDetectionConfig config = buildConfig();
            when(managePiiDetectionConfigPort.getConfig()).thenReturn(config);

            // Act
            var response = controller.getConfig();

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                softly.assertThat(response.getBody()).isNotNull();

                PiiDetectionConfigResponseDto body = response.getBody();
                softly.assertThat(body.glinerEnabled()).isTrue();
                softly.assertThat(body.presidioEnabled()).isTrue();
                softly.assertThat(body.regexEnabled()).isFalse();
                softly.assertThat(body.defaultThreshold()).isEqualByComparingTo(new BigDecimal("0.75"));
                softly.assertThat(body.nbOfLabelByPass()).isEqualTo(3);
                softly.assertThat(body.llmValidationEnabled()).isFalse();
                softly.assertThat(body.updatedAt()).isEqualTo(UPDATED_AT);
                softly.assertThat(body.updatedBy()).isEqualTo("admin");
            });
        }

        @Test
        @DisplayName("Should_Return500_When_ExceptionOccursDuringGetConfig")
        void Should_Return500_When_ExceptionOccursDuringGetConfig() {
            // Arrange
            when(managePiiDetectionConfigPort.getConfig())
                    .thenThrow(new RuntimeException("Database connection failed"));

            // Act
            var response = controller.getConfig();

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                softly.assertThat(response.getBody()).isNull();
            });
        }

        @Test
        @DisplayName("Should_CallPortGetConfig_When_GetConfigInvoked")
        void Should_CallPortGetConfig_When_GetConfigInvoked() {
            // Arrange
            when(managePiiDetectionConfigPort.getConfig()).thenReturn(buildConfig());

            // Act
            controller.getConfig();

            // Assert
            verify(managePiiDetectionConfigPort).getConfig();
        }
    }

    @Nested
    @DisplayName("updateConfig")
    class UpdateConfig {

        private UpdatePiiDetectionConfigRequestDto buildValidRequest() {
            return new UpdatePiiDetectionConfigRequestDto(
                    true,
                    false,
                    true,
                    new BigDecimal("0.80"),
                    5,
                    true
            );
        }

        @Test
        @DisplayName("Should_Return200WithUpdatedConfig_When_ValidRequest")
        void Should_Return200WithUpdatedConfig_When_ValidRequest() {
            // Arrange
            UpdatePiiDetectionConfigRequestDto request = buildValidRequest();
            PiiDetectionConfig updatedConfig = new PiiDetectionConfig(
                    1, true, false, true,
                    new BigDecimal("0.80"), 5, true,
                    UPDATED_AT, "admin"
            );
            when(managePiiDetectionConfigPort.updateConfig(any(UpdatePiiDetectionConfigCommand.class)))
                    .thenReturn(updatedConfig);

            // Act
            var response = controller.updateConfig(request);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                softly.assertThat(response.getBody()).isNotNull();

                PiiDetectionConfigResponseDto body = response.getBody();
                softly.assertThat(body.glinerEnabled()).isTrue();
                softly.assertThat(body.presidioEnabled()).isFalse();
                softly.assertThat(body.regexEnabled()).isTrue();
                softly.assertThat(body.defaultThreshold()).isEqualByComparingTo(new BigDecimal("0.80"));
                softly.assertThat(body.nbOfLabelByPass()).isEqualTo(5);
                softly.assertThat(body.llmValidationEnabled()).isTrue();
                softly.assertThat(body.updatedBy()).isEqualTo("admin");
            });
        }

        @Test
        @DisplayName("Should_PassCorrectCommandToPort_When_UpdateConfigCalled")
        void Should_PassCorrectCommandToPort_When_UpdateConfigCalled() {
            // Arrange
            UpdatePiiDetectionConfigRequestDto request = buildValidRequest();
            when(managePiiDetectionConfigPort.updateConfig(any(UpdatePiiDetectionConfigCommand.class)))
                    .thenReturn(buildConfig());

            // Act
            controller.updateConfig(request);

            // Assert
            verify(managePiiDetectionConfigPort).updateConfig(
                    new UpdatePiiDetectionConfigCommand(
                            true, false, true,
                            new BigDecimal("0.80"), 5, true,
                            PiiDetectionConfigController.ADMIN_USERNAME
                    )
            );
        }

        @Test
        @DisplayName("Should_Return400_When_IllegalArgumentExceptionThrown")
        void Should_Return400_When_IllegalArgumentExceptionThrown() {
            // Arrange
            UpdatePiiDetectionConfigRequestDto request = buildValidRequest();
            when(managePiiDetectionConfigPort.updateConfig(any(UpdatePiiDetectionConfigCommand.class)))
                    .thenThrow(new IllegalArgumentException("Invalid threshold value"));

            // Act
            var response = controller.updateConfig(request);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                softly.assertThat(response.getBody()).isNull();
            });
        }

        @Test
        @DisplayName("Should_Return500_When_GenericExceptionThrownDuringUpdate")
        void Should_Return500_When_GenericExceptionThrownDuringUpdate() {
            // Arrange
            UpdatePiiDetectionConfigRequestDto request = buildValidRequest();
            when(managePiiDetectionConfigPort.updateConfig(any(UpdatePiiDetectionConfigCommand.class)))
                    .thenThrow(new RuntimeException("Unexpected database error"));

            // Act
            var response = controller.updateConfig(request);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                softly.assertThat(response.getBody()).isNull();
            });
        }

        @Test
        @DisplayName("Should_UseAdminUsername_When_UpdateConfigCalled")
        void Should_UseAdminUsername_When_UpdateConfigCalled() {
            // Arrange
            UpdatePiiDetectionConfigRequestDto request = buildValidRequest();
            when(managePiiDetectionConfigPort.updateConfig(any(UpdatePiiDetectionConfigCommand.class)))
                    .thenReturn(buildConfig());

            // Act
            controller.updateConfig(request);

            // Assert
            verify(managePiiDetectionConfigPort).updateConfig(
                    org.mockito.ArgumentMatchers.argThat(command ->
                            command.updatedBy().equals(PiiDetectionConfigController.ADMIN_USERNAME)
                    )
            );
        }
    }
}
