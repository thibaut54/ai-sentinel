package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.sharepoint.SharePointConnectionSettings;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.jpa.SharePointConnectionConfigJpaRepository;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.jpa.entity.SharePointConnectionConfigEntity;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SharePointConnectionConfigPersistenceAdapterTest {

    @Mock
    private SharePointConnectionConfigJpaRepository jpaRepository;

    private SharePointConnectionConfigPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SharePointConnectionConfigPersistenceAdapter(jpaRepository);
    }

    @Test
    void Should_ReturnSettings_When_ConfigExists() {
        // Arrange
        SharePointConnectionConfigEntity entity = SharePointConnectionConfigEntity.builder()
                .id(1)
                .tenantId("tenant-123")
                .clientId("client-456")
                .clientSecretEncrypted("encrypted")
                .enabled(true)
                .updatedAt(Instant.now())
                .updatedBy("admin")
                .build();
        when(jpaRepository.findById(1)).thenReturn(Optional.of(entity));

        // Act
        Optional<SharePointConnectionSettings> result = adapter.findSettings();

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().tenantId()).isEqualTo("tenant-123");
        assertThat(result.get().clientId()).isEqualTo("client-456");
        assertThat(result.get().enabled()).isTrue();
    }

    @Test
    void Should_ReturnEmpty_When_ConfigDoesNotExist() {
        // Arrange
        when(jpaRepository.findById(1)).thenReturn(Optional.empty());

        // Act
        Optional<SharePointConnectionSettings> result = adapter.findSettings();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ThrowException_When_SavingNullSettings() {
        // Act & Assert
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void Should_SaveAndReturnSettings_When_ValidSettingsProvided() {
        // Arrange
        Instant now = Instant.now();
        SharePointConnectionSettings settings = new SharePointConnectionSettings(
                1L, "tenant-123", "client-456", true, now, "admin");

        when(jpaRepository.findById(1)).thenReturn(Optional.empty());

        SharePointConnectionConfigEntity savedEntity = SharePointConnectionConfigEntity.builder()
                .id(1)
                .tenantId("tenant-123")
                .clientId("client-456")
                .clientSecretEncrypted("")
                .enabled(true)
                .updatedAt(now)
                .updatedBy("admin")
                .build();
        when(jpaRepository.save(any())).thenReturn(savedEntity);

        // Act
        SharePointConnectionSettings result = adapter.save(settings);

        // Assert
        assertThat(result.tenantId()).isEqualTo("tenant-123");
        verify(jpaRepository).save(any(SharePointConnectionConfigEntity.class));
    }

    @Test
    void Should_SaveEncryptedSecret_When_ConfigRowExists() {
        // Arrange
        SharePointConnectionConfigEntity entity = SharePointConnectionConfigEntity.builder()
                .id(1)
                .tenantId("tenant-123")
                .clientId("client-456")
                .clientSecretEncrypted("")
                .enabled(true)
                .build();
        when(jpaRepository.findById(1)).thenReturn(Optional.of(entity));

        // Act
        adapter.saveEncryptedClientSecret("new-encrypted-secret");

        // Assert
        ArgumentCaptor<SharePointConnectionConfigEntity> captor = ArgumentCaptor.forClass(SharePointConnectionConfigEntity.class);
        verify(jpaRepository).save(captor.capture());
        assertThat(captor.getValue().getClientSecretEncrypted()).isEqualTo("new-encrypted-secret");
    }

    @Test
    void Should_ThrowException_When_SaveSecretAndNoConfigRow() {
        // Arrange
        when(jpaRepository.findById(1)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> adapter.saveEncryptedClientSecret("encrypted"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void Should_ReturnEncryptedSecret_When_SecretExists() {
        // Arrange
        SharePointConnectionConfigEntity entity = SharePointConnectionConfigEntity.builder()
                .id(1)
                .clientSecretEncrypted("encrypted-value")
                .build();
        when(jpaRepository.findById(1)).thenReturn(Optional.of(entity));

        // Act
        Optional<String> result = adapter.findEncryptedClientSecret();

        // Assert
        assertThat(result).isPresent().hasValue("encrypted-value");
    }

    @Test
    void Should_ReturnEmpty_When_SecretIsEmpty() {
        // Arrange
        SharePointConnectionConfigEntity entity = SharePointConnectionConfigEntity.builder()
                .id(1)
                .clientSecretEncrypted("")
                .build();
        when(jpaRepository.findById(1)).thenReturn(Optional.of(entity));

        // Act
        Optional<String> result = adapter.findEncryptedClientSecret();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_HandleNullEncryptedSecret_When_SaveCalled() {
        // Arrange
        SharePointConnectionConfigEntity entity = SharePointConnectionConfigEntity.builder()
                .id(1).build();
        when(jpaRepository.findById(1)).thenReturn(Optional.of(entity));

        // Act
        adapter.saveEncryptedClientSecret(null);

        // Assert
        ArgumentCaptor<SharePointConnectionConfigEntity> captor = ArgumentCaptor.forClass(SharePointConnectionConfigEntity.class);
        verify(jpaRepository).save(captor.capture());
        assertThat(captor.getValue().getClientSecretEncrypted()).isEmpty();
    }
}
