package pro.softcom.aisentinel.application.jira.port.out;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JiraUrlProvider} interface.
 * Tests a simple in-memory implementation to verify the contract.
 */
class JiraUrlProviderTest {

    @Nested
    class InterfaceContract {

        @Test
        void Should_ReturnBaseUrl_When_ImplementationProvided() {
            // Arrange
            JiraUrlProvider provider = createProvider("https://jira.example.com");

            // Act
            String result = provider.baseUrl();

            // Assert
            assertThat(result)
                .as("baseUrl() should return the configured base URL")
                .isEqualTo("https://jira.example.com");
        }

        @Test
        void Should_ReturnIssueUrl_When_KeyIsValid() {
            // Arrange
            JiraUrlProvider provider = createProvider("https://jira.example.com");

            // Act
            String result = provider.issueUrl("PROJ-123");

            // Assert
            assertThat(result)
                .as("issueUrl() should build a valid Jira browse URL")
                .isEqualTo("https://jira.example.com/browse/PROJ-123");
        }

        @Test
        void Should_ReturnNull_When_KeyIsNull() {
            // Arrange
            JiraUrlProvider provider = createProvider("https://jira.example.com");

            // Act
            String result = provider.issueUrl(null);

            // Assert
            assertThat(result)
                .as("issueUrl() should return null for null key")
                .isNull();
        }
    }

    /**
     * Simple test implementation of JiraUrlProvider for contract verification.
     */
    private JiraUrlProvider createProvider(String baseUrl) {
        return new JiraUrlProvider() {
            @Override
            public String baseUrl() {
                return baseUrl;
            }

            @Override
            public String issueUrl(String issueKey) {
                if (issueKey == null || issueKey.isBlank()) {
                    return null;
                }
                return baseUrl + "/browse/" + issueKey;
            }
        };
    }
}
