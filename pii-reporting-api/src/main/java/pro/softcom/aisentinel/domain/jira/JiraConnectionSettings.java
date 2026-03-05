package pro.softcom.aisentinel.domain.jira;

import java.time.Instant;

/**
 * Domain model for Jira connection configuration.
 * Represents the connection settings for the Jira integration.
 * This is the single source of truth for connection configuration in the system.
 *
 * <p>Note: The API token is NOT part of the domain model because it is an infrastructure
 * concern (encrypted storage). The domain only deals with non-sensitive connection parameters.
 *
 * @param id             Configuration identifier (always 1, singleton)
 * @param baseUrl        Base URL of the Jira instance
 * @param email          Email for Jira authentication (Jira uses email, not username)
 * @param connectTimeout Connection timeout in milliseconds
 * @param readTimeout    Read timeout in milliseconds
 * @param maxRetries     Maximum number of retry attempts
 * @param issuesLimit    Number of issues per pagination request (max 100)
 * @param maxIssues      Maximum total issues to retrieve per project
 * @param updatedAt      Timestamp of last configuration update
 * @param updatedBy      User who last updated the configuration
 */
public record JiraConnectionSettings(
    Integer id,
    String baseUrl,
    String email,
    int connectTimeout,
    int readTimeout,
    int maxRetries,
    int issuesLimit,
    int maxIssues,
    Instant updatedAt,
    String updatedBy
) {
    public JiraConnectionSettings {
        if (baseUrl == null) baseUrl = "";
        if (email == null) email = "";
        if (connectTimeout <= 0) throw new IllegalArgumentException("Connect timeout must be positive");
        if (readTimeout <= 0) throw new IllegalArgumentException("Read timeout must be positive");
        if (maxRetries < 0) throw new IllegalArgumentException("Max retries cannot be negative");
        if (issuesLimit <= 0) throw new IllegalArgumentException("Issues limit must be positive");
        if (maxIssues <= 0) throw new IllegalArgumentException("Max issues must be positive");
    }
}
