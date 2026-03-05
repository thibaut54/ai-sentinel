package pro.softcom.aisentinel.infrastructure.jira.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO for testing Jira connection via REST API.
 *
 * @param baseUrl  Base URL of the Jira instance
 * @param email    Email for Jira authentication
 * @param apiToken Plain-text API token for authentication
 */
public record TestJiraConnectionRequestDto(
        @JsonProperty("baseUrl")
        @NotBlank(message = "baseUrl is required")
        String baseUrl,

        @JsonProperty("email")
        @NotBlank(message = "email is required")
        String email,

        @JsonProperty("apiToken")
        @NotBlank(message = "apiToken is required")
        String apiToken
) {
}
