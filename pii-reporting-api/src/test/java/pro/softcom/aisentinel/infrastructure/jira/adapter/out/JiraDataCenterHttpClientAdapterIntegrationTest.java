package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import pro.softcom.aisentinel.domain.jira.JiraDeploymentType;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

import java.util.concurrent.TimeUnit;

/**
 * Integration test for JiraDataCenterHttpClientAdapter against a real Jira Data Center instance.
 * <p>
 * Requires the following environment variables (see application-integration.yml):
 * <ul>
 *   <li>{@code JIRA_DC_BASE_URL} — e.g. https://jira.softcom.pro</li>
 *   <li>{@code JIRA_DC_USERNAME} — e.g. tvuillaume</li>
 *   <li>{@code JIRA_DC_API_TOKEN} — Personal Access Token (PAT)</li>
 * </ul>
 * Optionally:
 * <ul>
 *   <li>{@code JIRA_DC_PROJECT_KEY} — a project key to test issue retrieval (default: first available project)</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "JIRA_DC_BASE_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JIRA_DC_USERNAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "JIRA_DC_API_TOKEN", matches = ".+")
class JiraDataCenterHttpClientAdapterIntegrationTest {

    private static JiraDataCenterHttpClientAdapter adapter;
    private static String projectKey;

    @BeforeAll
    static void setUp() {
        var baseUrl = System.getenv("JIRA_DC_BASE_URL");
        var username = System.getenv("JIRA_DC_USERNAME");
        var apiToken = System.getenv("JIRA_DC_API_TOKEN");
        projectKey = System.getenv("JIRA_DC_PROJECT_KEY");

        var connectTimeout = intEnvOrDefault("JIRA_DC_CONNECT_TIMEOUT", 10000);
        var readTimeout = intEnvOrDefault("JIRA_DC_READ_TIMEOUT", 30000);
        var maxRetries = intEnvOrDefault("JIRA_DC_MAX_RETRIES", 2);
        var issuesLimit = intEnvOrDefault("JIRA_DC_ISSUES_LIMIT", 10);
        var maxIssues = intEnvOrDefault("JIRA_DC_MAX_ISSUES", 50);

        JiraConnectionConfig config = new JiraConnectionConfig() {
            @Override public String baseUrl() { return baseUrl; }
            @Override public String email() { return username; }
            @Override public String apiToken() { return apiToken; }
            @Override public int connectTimeout() { return connectTimeout; }
            @Override public int readTimeout() { return readTimeout; }
            @Override public int maxRetries() { return maxRetries; }
            @Override public int issuesLimit() { return issuesLimit; }
            @Override public int maxIssues() { return maxIssues; }
            @Override public JiraDeploymentType deploymentType() { return JiraDeploymentType.DATA_CENTER; }
        };

        adapter = new JiraDataCenterHttpClientAdapter(config, new ObjectMapper());
    }

    @Nested
    class TestConnection {

        @Test
        void Should_ReturnTrue_When_CredentialsAreValid() throws Exception {
            var result = adapter.testConnection().get(30, TimeUnit.SECONDS);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result)
                .as("Connection to Jira DC should succeed with valid credentials")
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
                .as("Jira DC instance should have at least one project")
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

    /**
     * Resolves the project key to use: either from env var or the first project found.
     */
    private static String resolveProjectKey() throws Exception {
        if (projectKey != null && !projectKey.isBlank()) {
            return projectKey;
        }
        var projects = adapter.getAllProjects().get(30, TimeUnit.SECONDS);
        if (projects.isEmpty()) {
            throw new IllegalStateException("No projects found on Jira DC instance — cannot run issue tests");
        }
        return projects.getFirst().key();
    }

    private static int intEnvOrDefault(String name, int defaultValue) {
        var value = System.getenv(name);
        return value != null && !value.isBlank() ? Integer.parseInt(value) : defaultValue;
    }
}
