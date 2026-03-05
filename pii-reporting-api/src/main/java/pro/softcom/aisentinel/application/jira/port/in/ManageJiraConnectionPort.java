package pro.softcom.aisentinel.application.jira.port.in;

import pro.softcom.aisentinel.domain.jira.JiraConnectionSettings;

import java.util.Objects;

/**
 * Port IN for managing Jira connection configuration.
 * Defines use cases for retrieving, updating, and testing Jira connection settings.
 */
public interface ManageJiraConnectionPort {

    JiraConnectionSettings getConnectionSettings();

    boolean isConfigured();

    JiraConnectionSettings updateConnectionSettings(UpdateJiraConnectionCommand command);

    boolean testConnection(TestJiraConnectionCommand command);

    record UpdateJiraConnectionCommand(
            String baseUrl,
            String email,
            String apiToken,
            int connectTimeout,
            int readTimeout,
            int maxRetries,
            int issuesLimit,
            int maxIssues,
            String updatedBy
    ) {
        public UpdateJiraConnectionCommand {
            Objects.requireNonNull(baseUrl, "baseUrl must not be null");
            Objects.requireNonNull(email, "email must not be null");
            Objects.requireNonNull(updatedBy, "updatedBy must not be null");
        }
    }

    record TestJiraConnectionCommand(
            String baseUrl,
            String email,
            String apiToken
    ) {
        public TestJiraConnectionCommand {
            Objects.requireNonNull(baseUrl, "baseUrl must not be null");
            Objects.requireNonNull(email, "email must not be null");
        }
    }
}
