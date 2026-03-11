package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import pro.softcom.aisentinel.application.sharepoint.port.in.ManageSharePointConnectionPort;
import pro.softcom.aisentinel.domain.sharepoint.SharePointConnectionSettings;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.dto.SharePointConnectionConfigResponseDto;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.dto.TestSharePointConnectionRequestDto;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.dto.UpdateSharePointConnectionConfigRequestDto;

import java.security.Principal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharePointConnectionConfigControllerTest {

    @Mock
    private ManageSharePointConnectionPort manageSharePointConnectionPort;

    @Mock
    private Principal principal;

    private SharePointConnectionConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new SharePointConnectionConfigController(manageSharePointConnectionPort);
    }

    @Test
    void Should_ReturnOk_When_GetConfigSuccessful() {
        // Arrange
        SharePointConnectionSettings settings = new SharePointConnectionSettings(
                1L, "tenant-123", "client-456", true, Instant.now(), "admin");
        when(manageSharePointConnectionPort.getConnectionSettings()).thenReturn(settings);
        when(manageSharePointConnectionPort.isConfigured()).thenReturn(true);

        // Act
        ResponseEntity<SharePointConnectionConfigResponseDto> result = controller.getConfig().join();

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().tenantId()).isEqualTo("tenant-123");
        assertThat(result.getBody().clientId()).isEqualTo("client-456");
        assertThat(result.getBody().clientSecretMasked()).isEqualTo("***");
        assertThat(result.getBody().configured()).isTrue();
    }

    @Test
    void Should_ReturnInternalServerError_When_GetConfigFails() {
        // Arrange
        when(manageSharePointConnectionPort.getConnectionSettings()).thenThrow(new RuntimeException("DB error"));

        // Act
        ResponseEntity<SharePointConnectionConfigResponseDto> result = controller.getConfig().join();

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void Should_ReturnOk_When_UpdateConfigSuccessful() {
        // Arrange
        UpdateSharePointConnectionConfigRequestDto request = new UpdateSharePointConnectionConfigRequestDto(
                "tenant-123", "client-456", "secret", true);
        SharePointConnectionSettings updatedSettings = new SharePointConnectionSettings(
                1L, "tenant-123", "client-456", true, Instant.now(), "admin");
        when(manageSharePointConnectionPort.updateConnectionSettings(any())).thenReturn(updatedSettings);
        when(manageSharePointConnectionPort.isConfigured()).thenReturn(true);
        when(principal.getName()).thenReturn("admin");

        // Act
        ResponseEntity<SharePointConnectionConfigResponseDto> result = controller.updateConfig(request, principal).join();

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().tenantId()).isEqualTo("tenant-123");
    }

    @Test
    void Should_UseSystemUser_When_PrincipalIsNull() {
        // Arrange
        UpdateSharePointConnectionConfigRequestDto request = new UpdateSharePointConnectionConfigRequestDto(
                "tenant-123", "client-456", null, false);
        SharePointConnectionSettings updatedSettings = new SharePointConnectionSettings(
                1L, "tenant-123", "client-456", false, Instant.now(), "system");
        when(manageSharePointConnectionPort.updateConnectionSettings(any())).thenReturn(updatedSettings);
        when(manageSharePointConnectionPort.isConfigured()).thenReturn(false);

        // Act
        ResponseEntity<SharePointConnectionConfigResponseDto> result = controller.updateConfig(request, null).join();

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void Should_ReturnBadRequest_When_UpdateConfigThrowsIllegalArgument() {
        // Arrange
        UpdateSharePointConnectionConfigRequestDto request = new UpdateSharePointConnectionConfigRequestDto(
                "tenant-123", "client-456", null, true);
        when(manageSharePointConnectionPort.updateConnectionSettings(any()))
                .thenThrow(new IllegalArgumentException("Invalid config"));
        when(principal.getName()).thenReturn("admin");

        // Act
        ResponseEntity<SharePointConnectionConfigResponseDto> result = controller.updateConfig(request, principal).join();

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void Should_ReturnOk_When_TestConnectionSucceeds() {
        // Arrange
        TestSharePointConnectionRequestDto request = new TestSharePointConnectionRequestDto(
                "tenant-123", "client-456", "secret");
        when(manageSharePointConnectionPort.testConnection(any())).thenReturn(true);

        // Act
        ResponseEntity<SharePointConnectionConfigController.ConnectionTestResultDto> result =
                controller.testConnection(request).join();

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().success()).isTrue();
        assertThat(result.getBody().message()).contains("successfully");
    }

    @Test
    void Should_ReturnOkWithFailure_When_TestConnectionFails() {
        // Arrange
        TestSharePointConnectionRequestDto request = new TestSharePointConnectionRequestDto(
                "tenant-123", "client-456", "secret");
        when(manageSharePointConnectionPort.testConnection(any())).thenReturn(false);

        // Act
        ResponseEntity<SharePointConnectionConfigController.ConnectionTestResultDto> result =
                controller.testConnection(request).join();

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().success()).isFalse();
        assertThat(result.getBody().message()).contains("Failed");
    }

    @Test
    void Should_ReturnInternalServerError_When_TestConnectionThrows() {
        // Arrange
        TestSharePointConnectionRequestDto request = new TestSharePointConnectionRequestDto(
                "tenant-123", "client-456", "secret");
        when(manageSharePointConnectionPort.testConnection(any())).thenThrow(new RuntimeException("Network error"));

        // Act
        ResponseEntity<SharePointConnectionConfigController.ConnectionTestResultDto> result =
                controller.testConnection(request).join();

        // Assert
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().success()).isFalse();
    }
}
