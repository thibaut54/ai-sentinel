package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.jira.JiraConnectionSettings;
import pro.softcom.aisentinel.domain.jira.JiraDeploymentType;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.jpa.JiraConnectionConfigJpaRepository;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.jpa.entity.JiraConnectionConfigEntity;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraConnectionConfigPersistenceAdapter")
class JiraConnectionConfigPersistenceAdapterTest {

    @Mock
    private JiraConnectionConfigJpaRepository jpaRepository;

    private JiraConnectionConfigPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JiraConnectionConfigPersistenceAdapter(jpaRepository);
    }

    // ========== findSettings ==========

    @Test
    @DisplayName("Should_ReturnSettings_When_ConfigExists")
    void Should_ReturnSettings_When_ConfigExists() {
        // Arrange
        JiraConnectionConfigEntity entity = createEntity();
        when(jpaRepository.findById(1)).thenReturn(Optional.of(entity));

        // Act
        Optional<JiraConnectionSettings> result = adapter.findSettings();

        // Assert
        assertSoftly(softly -> {
            softly.assertThat(result).isPresent();
            JiraConnectionSettings settings = result.get();
            softly.assertThat(settings.id()).isEqualTo(1);
            softly.assertThat(settings.baseUrl()).isEqualTo("https://test.atlassian.net");
            softly.assertThat(settings.email()).isEqualTo("user@test.com");
            softly.assertThat(settings.connectTimeout()).isEqualTo(30000);
            softly.assertThat(settings.readTimeout()).isEqualTo(60000);
            softly.assertThat(settings.maxRetries()).isEqualTo(3);
            softly.assertThat(settings.issuesLimit()).isEqualTo(50);
            softly.assertThat(settings.maxIssues()).isEqualTo(5000);
            softly.assertThat(settings.updatedBy()).isEqualTo("admin");
        });
    }

    @Test
    @DisplayName("Should_ReturnEmpty_When_ConfigDoesNotExist")
    void Should_ReturnEmpty_When_ConfigDoesNotExist() {
        // Arrange
        when(jpaRepository.findById(1)).thenReturn(Optional.empty());

        // Act
        Optional<JiraConnectionSettings> result = adapter.findSettings();

        // Assert
        assertThat(result).isEmpty();
    }

    // ========== save ==========

    @Test
    @DisplayName("Should_SaveAndReturnSettings_When_ValidSettings")
    void Should_SaveAndReturnSettings_When_ValidSettings() {
        // Arrange
        JiraConnectionSettings settings = createSettings();
        JiraConnectionConfigEntity existingEntity = createEntity();
        when(jpaRepository.findById(1)).thenReturn(Optional.of(existingEntity));
        when(jpaRepository.save(any(JiraConnectionConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        JiraConnectionSettings result = adapter.save(settings);

        // Assert
        assertSoftly(softly -> {
            softly.assertThat(result.baseUrl()).isEqualTo("https://test.atlassian.net");
            softly.assertThat(result.email()).isEqualTo("user@test.com");
            softly.assertThat(result.issuesLimit()).isEqualTo(50);
            softly.assertThat(result.maxIssues()).isEqualTo(5000);
        });
        verify(jpaRepository).save(any(JiraConnectionConfigEntity.class));
    }

    @Test
    @DisplayName("Should_ThrowException_When_SettingsIsNull")
    void Should_ThrowException_When_SettingsIsNull() {
        // Act & Assert
        assertThatThrownBy(() -> adapter.save(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Configuration cannot be null");
    }

    @Test
    @DisplayName("Should_UseEmptyToken_When_NoExistingEntity")
    void Should_UseEmptyToken_When_NoExistingEntity() {
        // Arrange
        JiraConnectionSettings settings = createSettings();
        when(jpaRepository.findById(1)).thenReturn(Optional.empty());
        when(jpaRepository.save(any(JiraConnectionConfigEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        // Act
        adapter.save(settings);

        // Assert
        verify(jpaRepository).save(argThat(entity ->
            entity.getApiTokenEncrypted().isEmpty()
        ));
    }

    // ========== saveEncryptedApiToken ==========

    @Test
    @DisplayName("Should_UpdateToken_When_EntityExists")
    void Should_UpdateToken_When_EntityExists() {
        // Arrange
        JiraConnectionConfigEntity entity = createEntity();
        when(jpaRepository.findById(1)).thenReturn(Optional.of(entity));
        when(jpaRepository.save(any(JiraConnectionConfigEntity.class))).thenReturn(entity);

        // Act
        adapter.saveEncryptedApiToken("encrypted-token-value");

        // Assert
        verify(jpaRepository).save(argThat(e ->
            "encrypted-token-value".equals(e.getApiTokenEncrypted())
        ));
    }

    @Test
    @DisplayName("Should_ThrowException_When_EntityNotFoundForTokenSave")
    void Should_ThrowException_When_EntityNotFoundForTokenSave() {
        // Arrange
        when(jpaRepository.findById(1)).thenReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> adapter.saveEncryptedApiToken("token"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Jira configuration row not found");
    }

    @Test
    @DisplayName("Should_SaveEmptyString_When_TokenIsNull")
    void Should_SaveEmptyString_When_TokenIsNull() {
        // Arrange
        JiraConnectionConfigEntity entity = createEntity();
        when(jpaRepository.findById(1)).thenReturn(Optional.of(entity));
        when(jpaRepository.save(any(JiraConnectionConfigEntity.class))).thenReturn(entity);

        // Act
        adapter.saveEncryptedApiToken(null);

        // Assert
        verify(jpaRepository).save(argThat(e ->
            "".equals(e.getApiTokenEncrypted())
        ));
    }

    // ========== findDecryptedApiToken ==========

    @Test
    @DisplayName("Should_ReturnToken_When_TokenExists")
    void Should_ReturnToken_When_TokenExists() {
        // Arrange
        JiraConnectionConfigEntity entity = createEntity();
        entity.setApiTokenEncrypted("enc-token");
        when(jpaRepository.findById(1)).thenReturn(Optional.of(entity));

        // Act
        Optional<String> result = adapter.findDecryptedApiToken();

        // Assert
        assertThat(result).isPresent().contains("enc-token");
    }

    @Test
    @DisplayName("Should_ReturnEmpty_When_TokenIsEmpty")
    void Should_ReturnEmpty_When_TokenIsEmpty() {
        // Arrange
        JiraConnectionConfigEntity entity = createEntity();
        entity.setApiTokenEncrypted("");
        when(jpaRepository.findById(1)).thenReturn(Optional.of(entity));

        // Act
        Optional<String> result = adapter.findDecryptedApiToken();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should_ReturnEmpty_When_EntityNotFound")
    void Should_ReturnEmpty_When_EntityNotFound() {
        // Arrange
        when(jpaRepository.findById(1)).thenReturn(Optional.empty());

        // Act
        Optional<String> result = adapter.findDecryptedApiToken();

        // Assert
        assertThat(result).isEmpty();
    }

    // ========== Helpers ==========

    private JiraConnectionConfigEntity createEntity() {
        return JiraConnectionConfigEntity.builder()
                .id(1)
                .baseUrl("https://test.atlassian.net")
                .email("user@test.com")
                .apiTokenEncrypted("enc-token")
                .connectTimeout(30000)
                .readTimeout(60000)
                .maxRetries(3)
                .issuesLimit(50)
                .maxIssues(5000)
                .deploymentType(JiraDeploymentType.CLOUD)
                .updatedAt(Instant.parse("2024-01-15T10:00:00Z"))
                .updatedBy("admin")
                .build();
    }

    private JiraConnectionSettings createSettings() {
        return new JiraConnectionSettings(
                1,
                "https://test.atlassian.net",
                "user@test.com",
                30000,
                60000,
                3,
                50,
                5000,
                JiraDeploymentType.CLOUD,
                Instant.parse("2024-01-15T10:00:00Z"),
                "admin"
        );
    }
}
