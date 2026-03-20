package pro.softcom.aisentinel.application.sharepoint.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.sharepoint.port.in.ManageSharePointConnectionPort.UpdateSharePointConnectionCommand;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointConnectionConfigRepository;
import pro.softcom.aisentinel.domain.pii.security.EncryptionService;
import pro.softcom.aisentinel.domain.sharepoint.SharePointConnectionSettings;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManageSharePointConnectionUseCaseTest {

    @Mock
    private SharePointConnectionConfigRepository repository;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private Runnable onConfigUpdated;

    private ManageSharePointConnectionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ManageSharePointConnectionUseCase(repository, encryptionService, onConfigUpdated);
    }

    @Test
    void Should_ReturnDefaultSettings_When_NoConfigExists() {
        // Arrange
        when(repository.findSettings()).thenReturn(Optional.empty());

        // Act
        SharePointConnectionSettings result = useCase.getConnectionSettings();

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.tenantId()).isEmpty();
        assertThat(result.clientId()).isEmpty();
        assertThat(result.enabled()).isFalse();
    }

    @Test
    void Should_ReturnExistingSettings_When_ConfigExists() {
        // Arrange
        SharePointConnectionSettings settings = new SharePointConnectionSettings(
                1L, "tenant-123", "client-456", true, Instant.now(), "admin");
        when(repository.findSettings()).thenReturn(Optional.of(settings));

        // Act
        SharePointConnectionSettings result = useCase.getConnectionSettings();

        // Assert
        assertThat(result.tenantId()).isEqualTo("tenant-123");
        assertThat(result.clientId()).isEqualTo("client-456");
        assertThat(result.enabled()).isTrue();
    }

    @Test
    void Should_ReturnTrue_When_ClientSecretIsConfigured() {
        // Arrange
        when(repository.findEncryptedClientSecret()).thenReturn(Optional.of("encrypted-secret"));

        // Act
        boolean result = useCase.isConfigured();

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    void Should_ReturnFalse_When_ClientSecretIsEmpty() {
        // Arrange
        when(repository.findEncryptedClientSecret()).thenReturn(Optional.of(""));

        // Act
        boolean result = useCase.isConfigured();

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    void Should_ReturnFalse_When_ClientSecretNotPresent() {
        // Arrange
        when(repository.findEncryptedClientSecret()).thenReturn(Optional.empty());

        // Act
        boolean result = useCase.isConfigured();

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    void Should_SaveSettingsAndEncryptSecret_When_UpdateWithSecret() {
        // Arrange
        UpdateSharePointConnectionCommand command = new UpdateSharePointConnectionCommand(
                "tenant-123", "client-456", "my-secret", true, "admin");
        when(encryptionService.encrypt(eq("my-secret"), any())).thenReturn("encrypted-secret");

        // Act
        SharePointConnectionSettings result = useCase.updateConnectionSettings(command);

        // Assert
        assertThat(result.tenantId()).isEqualTo("tenant-123");
        assertThat(result.clientId()).isEqualTo("client-456");
        assertThat(result.enabled()).isTrue();

        verify(repository).save(any(SharePointConnectionSettings.class));
        verify(encryptionService).encrypt(eq("my-secret"), any());
        verify(repository).saveEncryptedClientSecret("encrypted-secret");
        verify(onConfigUpdated).run();
    }

    @Test
    void Should_SaveSettingsWithoutEncrypting_When_UpdateWithoutSecret() {
        // Arrange
        UpdateSharePointConnectionCommand command = new UpdateSharePointConnectionCommand(
                "tenant-123", "client-456", null, false, "admin");

        // Act
        SharePointConnectionSettings result = useCase.updateConnectionSettings(command);

        // Assert
        assertThat(result.tenantId()).isEqualTo("tenant-123");
        verify(repository).save(any(SharePointConnectionSettings.class));
        verify(encryptionService, never()).encrypt(anyString(), any());
        verify(repository, never()).saveEncryptedClientSecret(anyString());
        verify(onConfigUpdated).run();
    }

    @Test
    void Should_NotEncryptSecret_When_SecretIsBlank() {
        // Arrange
        UpdateSharePointConnectionCommand command = new UpdateSharePointConnectionCommand(
                "tenant-123", "client-456", "   ", true, "admin");

        // Act
        useCase.updateConnectionSettings(command);

        // Assert
        verify(encryptionService, never()).encrypt(anyString(), any());
        verify(repository, never()).saveEncryptedClientSecret(anyString());
    }
}
