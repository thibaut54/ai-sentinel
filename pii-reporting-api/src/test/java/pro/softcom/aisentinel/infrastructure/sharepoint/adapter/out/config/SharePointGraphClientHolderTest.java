package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointConnectionConfigRepository;
import pro.softcom.aisentinel.domain.pii.security.EncryptionService;
import pro.softcom.aisentinel.domain.sharepoint.SharePointConnectionSettings;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharePointGraphClientHolderTest {

    @Mock
    private SharePointConnectionConfigRepository configRepository;

    @Mock
    private EncryptionService encryptionService;

    private SharePointGraphClientHolder holder;

    @BeforeEach
    void setUp() {
        holder = new SharePointGraphClientHolder(configRepository, encryptionService);
    }

    @Test
    void Should_ThrowException_When_ConfigNotFoundInDatabase() {
        // Arrange
        when(configRepository.findSettings()).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> holder.getClient())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void Should_ThrowException_When_ClientSecretNotFoundInDatabase() {
        // Arrange
        SharePointConnectionSettings settings = new SharePointConnectionSettings(
                1L, "tenant-123", "client-456", true, Instant.now(), "admin");
        when(configRepository.findSettings()).thenReturn(Optional.of(settings));
        when(configRepository.findEncryptedClientSecret()).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> holder.getClient())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client secret not found");
    }

    @Test
    void Should_ResetCachedClient_When_InvalidateCalled() {
        // Act
        holder.invalidate();

        // Assert - no exception thrown, and next call to getClient will create fresh
        // We verify by checking that calling getClient after invalidate will attempt to read from DB
        when(configRepository.findSettings()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> holder.getClient())
                .isInstanceOf(IllegalStateException.class);
        verify(configRepository).findSettings();
    }
}
