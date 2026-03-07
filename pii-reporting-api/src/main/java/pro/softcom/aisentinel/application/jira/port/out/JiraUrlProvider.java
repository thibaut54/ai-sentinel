package pro.softcom.aisentinel.application.jira.port.out;

/**
 * Application-level provider for building Jira URLs.
 * Keeps the application layer agnostic of infrastructure configuration.
 */
public interface JiraUrlProvider {
    String baseUrl();

    /**
     * Build a public issue URL for the given Jira issue key.
     * Returns null when issueKey or baseUrl is blank.
     */
    String issueUrl(String issueKey);
}
