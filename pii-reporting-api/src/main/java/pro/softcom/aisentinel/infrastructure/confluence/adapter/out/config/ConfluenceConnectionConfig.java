package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config;

import pro.softcom.aisentinel.domain.confluence.ConfluenceDeploymentType;

/**
 * Vendor-agnostic contract for Confluence connection and API settings.
 * Exposes scalar values to avoid coupling with vendor-specific config records.
 */
public interface ConfluenceConnectionConfig {
    // Core connection
    String baseUrl();
    String username();
    String apiToken();

    // Timeouts and retries
    int connectTimeout();
    int readTimeout();
    int maxRetries();

    // Pagination
    int pagesLimit();
    int maxPages();

    // Deployment type
    default ConfluenceDeploymentType deploymentType() {
        return ConfluenceDeploymentType.CLOUD;
    }

    // API paths — defaults match Confluence REST API v2 contract
    default String contentPath() { return "/content/"; }
    default String searchContentPath() { return "/content/search"; }
    default String spacePath() { return "/space"; }
    default String attachmentChildSuffix() { return "/child/attachment"; }
    default String defaultPageExpands() { return "body.storage,version,metadata,ancestors"; }
    default String defaultSpaceExpands() { return "permissions,metadata"; }

    // Convenience
    default boolean isValid() {
        if (!notBlank(baseUrl()) || !notBlank(apiToken())) {
            return false;
        }
        return deploymentType() == ConfluenceDeploymentType.DATA_CENTER || notBlank(username());
    }

    default String getRestApiUrl() {
        var base = baseUrl();
        return base.endsWith("/") ? base + "rest/api" : base + "/rest/api";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
