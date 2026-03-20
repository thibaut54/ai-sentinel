package pro.softcom.aisentinel.infrastructure.jira.adapter.in.dto;

import pro.softcom.aisentinel.domain.jira.JiraDeploymentType;

import java.time.Instant;

/**
 * DTO representing Jira connection configuration for REST API responses.
 *
 * <p>Business purpose: Provides clients with current Jira connection settings.
 * The API token is always masked for security.
 *
 * @param baseUrl        Base URL of the Jira instance
 * @param email          Email for Jira authentication
 * @param apiToken       Always masked as "***" for security
 * @param connectTimeout Connection timeout in milliseconds
 * @param readTimeout    Read timeout in milliseconds
 * @param maxRetries     Maximum number of retry attempts
 * @param issuesLimit    Number of issues per pagination request
 * @param maxIssues      Maximum total issues to retrieve per project
 * @param updatedAt      Timestamp of last configuration update
 * @param updatedBy      User who last updated the configuration
 * @param configured     Whether real credentials have been saved
 */
public record JiraConnectionConfigResponseDto(
        String baseUrl,
        String email,
        String apiToken,
        int connectTimeout,
        int readTimeout,
        int maxRetries,
        int issuesLimit,
        int maxIssues,
        JiraDeploymentType deploymentType,
        Instant updatedAt,
        String updatedBy,
        boolean configured
) {
}
