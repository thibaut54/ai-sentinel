package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import pro.softcom.aisentinel.application.jira.service.AdfContentParser;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

import java.util.concurrent.TimeUnit;

/**
 * Integration test for JiraCloudHttpClientAdapter against a real Jira Cloud instance.
 * <p>
 * Requires the following environment variables (see application-integration.yml):
 * <ul>
 *   <li>{@code JIRA_CLOUD_BASE_URL} — e.g. https://mysite.atlassian.net</li>
 *   <li>{@code JIRA_CLOUD_EMAIL} — Atlassian account email</li>
 *   <li>{@code JIRA_CLOUD_API_TOKEN} — Atlassian API token</li>
 * </ul>
 * Optionally:
 * <ul>
 *   <li>{@code JIRA_CLOUD_PROJECT_KEY} — a project key to test issue retrieval (default: first available project)</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "JIRA_CLOUD_BASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JIRA_CLOUD_EMAIL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JIRA_CLOUD_API_TOKEN", matches = ".+")
class JiraCloudHttpClientAdapterIntegrationTest {

    private static JiraCloudHttpClientAdapter adapter;
    private static String projectKey;

    @BeforeAll
    static void setUp() {
        var baseUrl = System.getenv("JIRA_CLOUD_BASE_URL");
        var email = System.getenv("JIRA_CLOUD_EMAIL");
        var apiToken = System.getenv("JIRA_CLOUD_API_TOKEN");
        projectKey = System.getenv("JIRA_CLOUD_PROJECT_KEY");

        var connectTimeout = intEnvOrDefault("JIRA_CLOUD_CONNECT_TIMEOUT", 10000);
        var readTimeout = intEnvOrDefault("JIRA_CLOUD_READ_TIMEOUT", 30000);
        var maxRetries = intEnvOrDefault("JIRA_CLOUD_MAX_RETRIES", 2);
        var issuesLimit = intEnvOrDefault("JIRA_CLOUD_ISSUES_LIMIT", 10);
        var maxIssues = intEnvOrDefault("JIRA_CLOUD_MAX_ISSUES", 50);

        JiraConnectionConfig config = new JiraConnectionConfig() {
            @Override public String baseUrl() { return baseUrl; }
            @Override public String email() { return email; }
            @Override public String apiToken() { return apiToken; }
            @Override public int connectTimeout() { return connectTimeout; }
            @Override public int readTimeout() { return readTimeout; }
            @Override public int maxRetries() { return maxRetries; }
            @Override public int issuesLimit() { return issuesLimit; }
            @Override public int maxIssues() { return maxIssues; }
        };

        adapter = new JiraCloudHttpClientAdapter(config, new ObjectMapper(), new AdfContentParser());
    }

    @Nested
    class TestConnection {

        @Test
        void Should_ReturnTrue_When_CredentialsAreValid() throws Exception {
            var result = adapter.testConnection().get(30, TimeUnit.SECONDS);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result)
                .as("Connection to Jira Cloud should succeed with valid credentials")
                .isTrue();
            softly.assertAll();
        }
    }

    @Nested
    class GetAllProjects {

        @Test
        void Should_ReturnNonEmptyList_When_JiraHasProjects() throws Exception {
            var projects = adapter.getAllProjects().get(30, TimeUnit.SECONDS);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(projects)
                .as("Jira Cloud instance should have at least one project")
                .isNotEmpty();
            softly.assertThat(projects.getFirst().key())
                .as("Project key should not be blank")
                .isNotBlank();
            softly.assertThat(projects.getFirst().name())
                .as("Project name should not be blank")
                .isNotBlank();
            softly.assertAll();
        }
    }

    @Nested
    class GetIssuesInProject {

        @Test
        void Should_ReturnIssues_When_ProjectExists() throws Exception {
            var resolvedProjectKey = resolveProjectKey();

            var issues = adapter.getIssuesInProject(resolvedProjectKey).get(60, TimeUnit.SECONDS);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues)
                .as("Project %s should have at least one issue", resolvedProjectKey)
                .isNotEmpty();

            var firstIssue = issues.getFirst();
            softly.assertThat(firstIssue.key())
                .as("Issue key should start with project key")
                .startsWith(resolvedProjectKey + "-");
            softly.assertThat(firstIssue.summary())
                .as("Issue should have a summary")
                .isNotBlank();
            softly.assertThat(firstIssue.projectKey())
                .isEqualTo(resolvedProjectKey);
            softly.assertThat(firstIssue.metadata()).isNotNull();
            softly.assertThat(firstIssue.metadata().status())
                .as("Issue should have a status")
                .isNotBlank();

            // Verify ScannableContent contract
            softly.assertThat(firstIssue.getTitle())
                .as("ScannableContent title should not be blank")
                .isNotBlank();
            softly.assertThat(firstIssue.getSourceId())
                .as("ScannableContent sourceId should match project key")
                .isEqualTo(resolvedProjectKey);

            softly.assertAll();
        }
    }

    @Nested
    class GetAllComments {

        @Test
        void Should_ReturnCommentsWithoutError_When_IssueExists() throws Exception {
            var resolvedProjectKey = resolveProjectKey();
            var issues = adapter.getIssuesInProject(resolvedProjectKey).get(60, TimeUnit.SECONDS);

            var issueKey = issues.getFirst().key();
            var comments = adapter.getAllComments(issueKey).get(30, TimeUnit.SECONDS);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(comments)
                .as("Comments list should not be null (may be empty)")
                .isNotNull();

            if (!comments.isEmpty()) {
                softly.assertThat(comments.getFirst().id()).isNotBlank();
                softly.assertThat(comments.getFirst().authorDisplayName()).isNotBlank();
            }
            softly.assertAll();
        }
    }

    @Nested
    class GetAttachments {

        @Test
        void Should_ReturnAttachmentsWithoutError_When_IssueExists() throws Exception {
            var resolvedProjectKey = resolveProjectKey();
            var issues = adapter.getIssuesInProject(resolvedProjectKey).get(60, TimeUnit.SECONDS);

            var issueKey = issues.getFirst().key();
            var attachments = adapter.getAttachments(issueKey).get(30, TimeUnit.SECONDS);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(attachments)
                .as("Attachments list should not be null (may be empty)")
                .isNotNull();

            if (!attachments.isEmpty()) {
                softly.assertThat(attachments.getFirst().id()).isNotBlank();
                softly.assertThat(attachments.getFirst().filename()).isNotBlank();
            }
            softly.assertAll();
        }
    }

    private static String resolveProjectKey() throws Exception {
        if (projectKey != null && !projectKey.isBlank()) {
            return projectKey;
        }
        var projects = adapter.getAllProjects().get(30, TimeUnit.SECONDS);
        if (projects.isEmpty()) {
            throw new IllegalStateException("No projects found on Jira Cloud instance — cannot run issue tests");
        }
        return projects.getFirst().key();
    }

    private static int intEnvOrDefault(String name, int defaultValue) {
        var value = System.getenv(name);
        return value != null && !value.isBlank() ? Integer.parseInt(value) : defaultValue;
    }
}
