package pro.softcom.aisentinel.domain.jira;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JiraConnectionSettings")
class JiraConnectionSettingsTest {

    @Test
    @DisplayName("Should create settings when all values are valid")
    void Should_CreateSettings_When_AllValuesValid() {
        // Given / When
        var settings = new JiraConnectionSettings(
                1, "https://jira.example.com", "user@example.com",
                30000, 60000, 3, 50, 5000, null, Instant.now(), "admin");

        // Then
        assertThat(settings.baseUrl()).isEqualTo("https://jira.example.com");
        assertThat(settings.email()).isEqualTo("user@example.com");
        assertThat(settings.connectTimeout()).isEqualTo(30000);
        assertThat(settings.issuesLimit()).isEqualTo(50);
        assertThat(settings.maxIssues()).isEqualTo(5000);
    }

    @Test
    @DisplayName("Should default baseUrl to empty when null")
    void Should_DefaultBaseUrl_When_Null() {
        var settings = new JiraConnectionSettings(1, null, "email", 1000, 1000, 0, 10, 100, null, null, null);
        assertThat(settings.baseUrl()).isEmpty();
    }

    @Test
    @DisplayName("Should default email to empty when null")
    void Should_DefaultEmail_When_Null() {
        var settings = new JiraConnectionSettings(1, "url", null, 1000, 1000, 0, 10, 100, null, null, null);
        assertThat(settings.email()).isEmpty();
    }

    @Test
    @DisplayName("Should throw when connectTimeout is zero")
    void Should_Throw_When_ConnectTimeoutIsZero() {
        assertThatThrownBy(() -> new JiraConnectionSettings(1, "", "", 0, 1000, 0, 10, 100, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Connect timeout must be positive");
    }

    @Test
    @DisplayName("Should throw when connectTimeout is negative")
    void Should_Throw_When_ConnectTimeoutIsNegative() {
        assertThatThrownBy(() -> new JiraConnectionSettings(1, "", "", -1, 1000, 0, 10, 100, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Connect timeout must be positive");
    }

    @Test
    @DisplayName("Should throw when readTimeout is zero")
    void Should_Throw_When_ReadTimeoutIsZero() {
        assertThatThrownBy(() -> new JiraConnectionSettings(1, "", "", 1000, 0, 0, 10, 100, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Read timeout must be positive");
    }

    @Test
    @DisplayName("Should throw when maxRetries is negative")
    void Should_Throw_When_MaxRetriesIsNegative() {
        assertThatThrownBy(() -> new JiraConnectionSettings(1, "", "", 1000, 1000, -1, 10, 100, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Max retries cannot be negative");
    }

    @Test
    @DisplayName("Should allow maxRetries of zero")
    void Should_AllowMaxRetriesOfZero() {
        var settings = new JiraConnectionSettings(1, "", "", 1000, 1000, 0, 10, 100, null, null, null);
        assertThat(settings.maxRetries()).isZero();
    }

    @Test
    @DisplayName("Should throw when issuesLimit is zero")
    void Should_Throw_When_IssuesLimitIsZero() {
        assertThatThrownBy(() -> new JiraConnectionSettings(1, "", "", 1000, 1000, 0, 0, 100, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Issues limit must be positive");
    }

    @Test
    @DisplayName("Should throw when maxIssues is zero")
    void Should_Throw_When_MaxIssuesIsZero() {
        assertThatThrownBy(() -> new JiraConnectionSettings(1, "", "", 1000, 1000, 0, 10, 0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Max issues must be positive");
    }

    @Test
    @DisplayName("Should throw when maxIssues is negative")
    void Should_Throw_When_MaxIssuesIsNegative() {
        assertThatThrownBy(() -> new JiraConnectionSettings(1, "", "", 1000, 1000, 0, 10, -5, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Max issues must be positive");
    }
}
