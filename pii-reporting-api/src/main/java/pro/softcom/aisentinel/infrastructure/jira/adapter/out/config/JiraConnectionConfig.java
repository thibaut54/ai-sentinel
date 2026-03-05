package pro.softcom.aisentinel.infrastructure.jira.adapter.out.config;

/**
 * Vendor-agnostic contract for Jira connection and API settings.
 * Exposes scalar values to avoid coupling with vendor-specific config records.
 */
public interface JiraConnectionConfig {

    String baseUrl();

    String email();

    String apiToken();

    int connectTimeout();

    int readTimeout();

    int maxRetries();

    int issuesLimit();

    int maxIssues();

    default String getRestApiUrl() {
        var base = baseUrl();
        return base.endsWith("/") ? base + "rest/api/3" : base + "/rest/api/3";
    }

    default boolean isValid() {
        return notBlank(baseUrl()) && notBlank(email()) && notBlank(apiToken());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
