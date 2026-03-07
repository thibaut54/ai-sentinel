package pro.softcom.aisentinel.infrastructure.jira.adapter.out.config;

import pro.softcom.aisentinel.domain.jira.JiraDeploymentType;

/**
 * Vendor-agnostic contract for Jira connection and API settings.
 * Exposes scalar values to avoid coupling with vendor-specific config records.
 * <p>
 * API path defaults follow the same pattern as
 * {@link pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig}:
 * each REST resource fragment is a default method that can be overridden for testing or
 * alternative Jira flavours.
 */
@SuppressWarnings("java:S1075") // API paths are fixed by Jira REST API contract
public interface JiraConnectionConfig {

    String baseUrl();

    String email();

    String apiToken();

    int connectTimeout();

    int readTimeout();

    int maxRetries();

    int issuesLimit();

    int maxIssues();

    default JiraDeploymentType deploymentType() {
        return JiraDeploymentType.CLOUD;
    }

    default String getRestApiUrl() {
        var base = baseUrl();
        var version = deploymentType() == JiraDeploymentType.DATA_CENTER ? "2" : "3";
        return base.endsWith("/") ? base + "rest/api/" + version : base + "/rest/api/" + version;
    }

    // API path fragments -- defaults match Jira REST API v2/v3 contract

    default String myselfPath() { return "/myself"; }

    default String searchPath() { return "/search"; }

    default String projectPath() { return "/project"; }

    default String projectSearchPath() { return "/project/search"; }

    default String issuePath() { return "/issue/"; }

    default String attachmentPath() { return "/attachment/"; }

    default String attachmentContentPath() { return "/attachment/content/"; }

    default String commentPath() { return "/comment"; }

    default boolean isValid() {
        return notBlank(baseUrl()) && notBlank(email()) && notBlank(apiToken());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
