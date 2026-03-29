package pro.softcom.aisentinel.infrastructure.jira.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import pro.softcom.aisentinel.domain.jira.JiraDeploymentType;

/**
 * DTO for updating Jira connection configuration via REST API.
 *
 * @param baseUrl        Base URL of the Jira instance
 * @param email          Email for Jira authentication
 * @param apiToken       Plain-text API token (will be encrypted before storage)
 * @param connectTimeout Connection timeout in milliseconds
 * @param readTimeout    Read timeout in milliseconds
 * @param maxRetries     Maximum number of retry attempts
 * @param issuesLimit    Number of issues per pagination request
 * @param maxIssues      Maximum total issues to retrieve per project
 */
public record UpdateJiraConnectionConfigRequestDto(
        @JsonProperty("baseUrl")
        @NotBlank(message = "baseUrl is required")
        String baseUrl,

        @JsonProperty("email")
        @NotBlank(message = "email is required")
        String email,

        @JsonProperty("apiToken")
        String apiToken,

        @JsonProperty("connectTimeout")
        @NotNull(message = "connectTimeout is required")
        @Min(value = 1, message = "connectTimeout must be positive")
        Integer connectTimeout,

        @JsonProperty("readTimeout")
        @NotNull(message = "readTimeout is required")
        @Min(value = 1, message = "readTimeout must be positive")
        Integer readTimeout,

        @JsonProperty("maxRetries")
        @NotNull(message = "maxRetries is required")
        @Min(value = 0, message = "maxRetries cannot be negative")
        Integer maxRetries,

        @JsonProperty("issuesLimit")
        @NotNull(message = "issuesLimit is required")
        @Min(value = 1, message = "issuesLimit must be positive")
        Integer issuesLimit,

        @JsonProperty("maxIssues")
        @NotNull(message = "maxIssues is required")
        @Min(value = 1, message = "maxIssues must be positive")
        Integer maxIssues,

        @JsonProperty("deploymentType")
        @NotNull(message = "deploymentType is required")
        JiraDeploymentType deploymentType
) {
}
