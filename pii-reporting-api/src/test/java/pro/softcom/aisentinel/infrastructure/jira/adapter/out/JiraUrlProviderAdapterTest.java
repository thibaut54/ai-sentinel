package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JiraUrlProviderAdapter}.
 * Verifies URL construction for Jira issues based on connection configuration.
 */
@ExtendWith(MockitoExtension.class)
class JiraUrlProviderAdapterTest {

    @Mock
    private JiraConnectionConfig jiraConnectionConfig;

    private JiraUrlProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new JiraUrlProviderAdapter(jiraConnectionConfig);
    }

    @Nested
    class IssueUrl {

        @Test
        void Should_ReturnIssueUrl_When_KeyIsValid() {
            // Arrange
            when(jiraConnectionConfig.baseUrl()).thenReturn("https://jira.example.com");

            // Act
            String result = adapter.issueUrl("PROJ-123");

            // Assert
            assertThat(result)
                .as("Issue URL should combine base URL with /browse/ and the issue key")
                .isEqualTo("https://jira.example.com/browse/PROJ-123");
        }

        @Test
        void Should_ReturnNull_When_KeyIsBlank() {
            // Arrange - no config setup needed since key check happens first

            // Act
            String result = adapter.issueUrl("");

            // Assert
            assertThat(result)
                .as("Issue URL should be null when the issue key is blank")
                .isNull();
        }

        @Test
        void Should_ReturnNull_When_KeyIsNull() {
            // Arrange - no config setup needed since key check happens first

            // Act
            String result = adapter.issueUrl(null);

            // Assert
            assertThat(result)
                .as("Issue URL should be null when the issue key is null")
                .isNull();
        }

        @Test
        void Should_ReturnNull_When_BaseUrlIsBlank() {
            // Arrange
            when(jiraConnectionConfig.baseUrl()).thenReturn("");

            // Act
            String result = adapter.issueUrl("PROJ-123");

            // Assert
            assertThat(result)
                .as("Issue URL should be null when the base URL is blank")
                .isNull();
        }

        @Test
        void Should_ReturnNull_When_BaseUrlIsNull() {
            // Arrange
            when(jiraConnectionConfig.baseUrl()).thenReturn(null);

            // Act
            String result = adapter.issueUrl("PROJ-123");

            // Assert
            assertThat(result)
                .as("Issue URL should be null when the base URL is null")
                .isNull();
        }

        @Test
        void Should_TrimTrailingSlash_When_BaseUrlHasOne() {
            // Arrange
            when(jiraConnectionConfig.baseUrl()).thenReturn("https://jira.example.com/");

            // Act
            String result = adapter.issueUrl("PROJ-123");

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result)
                .as("Issue URL should not contain double slashes")
                .doesNotContain("//browse");
            softly.assertThat(result)
                .as("Issue URL should be correctly formed after trimming trailing slash")
                .isEqualTo("https://jira.example.com/browse/PROJ-123");
            softly.assertAll();
        }
    }

    @Nested
    class BaseUrl {

        @Test
        void Should_ReturnBaseUrl_When_ConfigProvided() {
            // Arrange
            when(jiraConnectionConfig.baseUrl()).thenReturn("https://jira.example.com");

            // Act
            String result = adapter.baseUrl();

            // Assert
            assertThat(result)
                .as("baseUrl should delegate to connection config")
                .isEqualTo("https://jira.example.com");
        }

        @Test
        void Should_ReturnNull_When_ConfigBaseUrlIsNull() {
            // Arrange
            when(jiraConnectionConfig.baseUrl()).thenReturn(null);

            // Act
            String result = adapter.baseUrl();

            // Assert
            assertThat(result)
                .as("baseUrl should return null when config returns null")
                .isNull();
        }
    }
}
