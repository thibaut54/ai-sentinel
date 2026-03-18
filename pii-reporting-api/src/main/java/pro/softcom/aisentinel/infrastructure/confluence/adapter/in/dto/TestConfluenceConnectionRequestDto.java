package pro.softcom.aisentinel.infrastructure.confluence.adapter.in.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import pro.softcom.aisentinel.domain.confluence.ConfluenceDeploymentType;

/**
 * DTO for testing Confluence connection via REST API.
 *
 * <p>Only requires connection credentials, not the full configuration.
 *
 * @param baseUrl        Base URL of the Confluence instance
 * @param username       Username for Confluence authentication
 * @param apiToken       Plain-text API token for authentication
 * @param deploymentType Type of Confluence deployment ("CLOUD" or "DATA_CENTER")
 */
public record TestConfluenceConnectionRequestDto(
        @JsonProperty("baseUrl")
        @NotBlank(message = "baseUrl is required")
        String baseUrl,

        @JsonProperty("username")
        String username,

        @JsonProperty("apiToken")
        @NotBlank(message = "apiToken is required")
        String apiToken,

        @JsonProperty("deploymentType")
        ConfluenceDeploymentType deploymentType
) {
}
