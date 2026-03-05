package pro.softcom.aisentinel.application.jira.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.jira.port.in.ManageJiraConnectionPort.TestJiraConnectionCommand;
import pro.softcom.aisentinel.application.jira.port.in.ManageJiraConnectionPort.UpdateJiraConnectionCommand;
import pro.softcom.aisentinel.application.jira.port.out.JiraConnectionConfigRepository;
import pro.softcom.aisentinel.domain.jira.JiraConnectionSettings;
import pro.softcom.aisentinel.domain.pii.security.EncryptionService;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ManageJiraConnectionUseCase")
class ManageJiraConnectionUseCaseTest {

    @Mock
    private JiraConnectionConfigRepository repository;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private Runnable onConfigUpdated;

    private ManageJiraConnectionUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ManageJiraConnectionUseCase(repository, encryptionService, onConfigUpdated);
    }

    @Nested
    @DisplayName("getConnectionSettings")
    class GetConnectionSettings {

        @Test
        @DisplayName("Should return settings from repository when present")
        void Should_ReturnSettings_When_RepositoryHasConfig() {
            // Given
            var expected = createSettings("https://example.atlassian.net");
            when(repository.findSettings()).thenReturn(Optional.of(expected));

            // When
            var result = useCase.getConnectionSettings();

            // Then
            assertThat(result).isEqualTo(expected);
            verify(repository).findSettings();
        }

        @Test
        @DisplayName("Should return default settings when repository is empty")
        void Should_ReturnDefaultSettings_When_RepositoryIsEmpty() {
            // Given
            when(repository.findSettings()).thenReturn(Optional.empty());

            // When
            var result = useCase.getConnectionSettings();

            // Then
            assertThat(result.baseUrl()).isEmpty();
            assertThat(result.email()).isEmpty();
            assertThat(result.connectTimeout()).isEqualTo(30000);
            assertThat(result.readTimeout()).isEqualTo(60000);
            assertThat(result.maxRetries()).isEqualTo(3);
            assertThat(result.issuesLimit()).isEqualTo(50);
            assertThat(result.maxIssues()).isEqualTo(5000);
        }
    }

    @Nested
    @DisplayName("isConfigured")
    class IsConfigured {

        @Test
        @DisplayName("Should return true when decrypted token exists")
        void Should_ReturnTrue_When_DecryptedTokenExists() {
            when(repository.findDecryptedApiToken()).thenReturn(Optional.of("plain-token"));

            assertThat(useCase.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("Should return false when decrypted token is empty")
        void Should_ReturnFalse_When_DecryptedTokenIsEmpty() {
            when(repository.findDecryptedApiToken()).thenReturn(Optional.empty());

            assertThat(useCase.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("Should return false when decrypted token is blank")
        void Should_ReturnFalse_When_DecryptedTokenIsBlank() {
            when(repository.findDecryptedApiToken()).thenReturn(Optional.of("   "));

            assertThat(useCase.isConfigured()).isFalse();
        }
    }

    @Nested
    @DisplayName("updateConnectionSettings")
    class UpdateConnectionSettings {

        @Test
        @DisplayName("Should update settings when URL is valid HTTPS")
        void Should_UpdateSettings_When_UrlIsValidHttps() {
            // Given
            var command = createUpdateCommand("https://mycompany.atlassian.net", "token123");
            when(encryptionService.encrypt(eq("token123"), any())).thenReturn("ENC:v1:encrypted");

            // When
            var result = useCase.updateConnectionSettings(command);

            // Then
            assertThat(result.baseUrl()).isEqualTo("https://mycompany.atlassian.net");
            verify(repository).save(any());
            verify(repository).saveEncryptedApiToken("ENC:v1:encrypted");
            verify(onConfigUpdated).run();
        }

        @Test
        @DisplayName("Should normalize trailing slash in URL")
        void Should_NormalizeTrailingSlash_When_UrlHasTrailingSlash() {
            // Given
            var command = createUpdateCommand("https://mycompany.atlassian.net/", "token123");
            when(encryptionService.encrypt(eq("token123"), any())).thenReturn("ENC:v1:encrypted");

            // When
            var result = useCase.updateConnectionSettings(command);

            // Then
            assertThat(result.baseUrl()).isEqualTo("https://mycompany.atlassian.net");
        }

        @ParameterizedTest(name = "Should reject unsafe URL: {0}")
        @CsvSource({
                "http://mycompany.atlassian.net, HTTPS",
                "https://192.168.1.1, private",
                "https://127.0.0.1, private"
        })
        void Should_RejectUrl_When_UrlIsUnsafe(String url, String expectedMessage) {
            var command = createUpdateCommand(url, "token");

            assertThatThrownBy(() -> useCase.updateConnectionSettings(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(expectedMessage);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Should not save token when no new token provided")
        void Should_NotSaveToken_When_NoNewTokenProvided() {
            // Given
            var command = createUpdateCommand("https://mycompany.atlassian.net", "");

            // When
            useCase.updateConnectionSettings(command);

            // Then
            verify(repository).save(any());
            verify(repository, never()).saveEncryptedApiToken(any());
            verify(encryptionService, never()).encrypt(any(), any());
        }

        @Test
        @DisplayName("Should not save token when token is null")
        void Should_NotSaveToken_When_TokenIsNull() {
            // Given
            var command = createUpdateCommand("https://mycompany.atlassian.net", null);

            // When
            useCase.updateConnectionSettings(command);

            // Then
            verify(repository).save(any());
            verify(repository, never()).saveEncryptedApiToken(any());
        }
    }

    @Nested
    @DisplayName("testConnection")
    class TestConnection {

        @Test
        @DisplayName("Should reject URL when host is private address (SSRF protection)")
        void Should_RejectUrl_When_HostIsPrivateAddress() {
            var command = new TestJiraConnectionCommand("https://10.0.0.1", "user@example.com", "token");

            assertThatThrownBy(() -> useCase.testConnection(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("private");
        }

        @Test
        @DisplayName("Should reject URL when scheme is HTTP (SSRF protection)")
        void Should_RejectUrl_When_SchemeIsHttp() {
            var command = new TestJiraConnectionCommand("http://public.example.com", "user@example.com", "token");

            assertThatThrownBy(() -> useCase.testConnection(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTPS");
        }

        @Test
        @DisplayName("Should reject URL when host is localhost (SSRF protection)")
        void Should_RejectUrl_When_HostIsLocalhost() {
            var command = new TestJiraConnectionCommand("https://localhost", "user@example.com", "token");

            assertThatThrownBy(() -> useCase.testConnection(command))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("private");
        }
    }

    private UpdateJiraConnectionCommand createUpdateCommand(String baseUrl, String apiToken) {
        return new UpdateJiraConnectionCommand(
                baseUrl, "user@example.com", apiToken,
                5000, 30000, 3, 50, 5000, "test-user"
        );
    }

    private JiraConnectionSettings createSettings(String baseUrl) {
        return new JiraConnectionSettings(
                1, baseUrl, "user@example.com",
                5000, 30000, 3, 50, 5000,
                Instant.now(), "test-user"
        );
    }
}
