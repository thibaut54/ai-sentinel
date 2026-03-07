package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.http.HttpRetryExecutor;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for JiraDataCenterHttpClientAdapter.
 * Tests cover: connection, project retrieval (flat array), issue search with wiki markup,
 * comments pagination, attachments, auth encoding, date parsing (DC format), and retry policy.
 */
@ExtendWith(MockitoExtension.class)
class JiraDataCenterHttpClientAdapterTest {

    @Mock
    private JiraConnectionConfig config;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private JiraDataCenterHttpClientAdapter adapter;

    @BeforeEach
    void setUp() {
        setupConfig();
        createAdapterWithMockedHttpClient();
    }

    private void setupConfig() {
        lenient().when(config.baseUrl()).thenReturn("https://jira.softcom.pro");
        lenient().when(config.email()).thenReturn("tvuillaume");
        lenient().when(config.apiToken()).thenReturn("test-password");
        lenient().when(config.connectTimeout()).thenReturn(5000);
        lenient().when(config.readTimeout()).thenReturn(10000);
        lenient().when(config.maxRetries()).thenReturn(3);
        lenient().when(config.issuesLimit()).thenReturn(50);
        lenient().when(config.maxIssues()).thenReturn(5000);
        lenient().when(config.getRestApiUrl()).thenReturn("https://jira.softcom.pro/rest/api/2");

        // Delegate API path default methods to the real interface defaults
        lenient().when(config.myselfPath()).thenCallRealMethod();
        lenient().when(config.searchPath()).thenCallRealMethod();
        lenient().when(config.projectPath()).thenCallRealMethod();
        lenient().when(config.projectSearchPath()).thenCallRealMethod();
        lenient().when(config.issuePath()).thenCallRealMethod();
        lenient().when(config.attachmentPath()).thenCallRealMethod();
        lenient().when(config.attachmentContentPath()).thenCallRealMethod();
        lenient().when(config.commentPath()).thenCallRealMethod();
    }

    private void createAdapterWithMockedHttpClient() {
        var objectMapper = new ObjectMapper();
        var retryExecutor = new HttpRetryExecutor(httpClient, config.maxRetries());
        adapter = new JiraDataCenterHttpClientAdapter(config, objectMapper, retryExecutor);

        lenient().when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandlers.ofString().getClass())))
            .thenReturn(CompletableFuture.completedFuture(httpResponse));
    }

    @Nested
    class TestConnection {

        @Test
        void Should_ReturnTrue_When_ConnectionSucceeds() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);

            var result = adapter.testConnection().get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result).isTrue();
            softly.assertAll();
        }

        @Test
        void Should_ReturnFalse_When_ConnectionFails() throws Exception {
            when(httpResponse.statusCode()).thenReturn(401);

            var result = adapter.testConnection().get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result).isFalse();
            softly.assertAll();
        }

        @Test
        void Should_ReturnFalse_When_ExceptionOccurs() throws Exception {
            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandlers.ofString().getClass())))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Network error")));

            var result = adapter.testConnection().get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result).isFalse();
            softly.assertAll();
        }

        @Test
        void Should_UseApiV2Endpoint_When_TestingConnection() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);

            adapter.testConnection().get();

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).sendAsync(captor.capture(), any(HttpResponse.BodyHandlers.ofString().getClass()));

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(captor.getValue().uri().toString())
                .isEqualTo("https://jira.softcom.pro/rest/api/2/myself");
            softly.assertAll();
        }
    }

    @Nested
    class GetAllProjects {

        @Test
        void Should_ReturnProjects_When_ApiReturnsFlatArray() throws Exception {
            var responseBody = createDcProjectsResponse(
                List.of(
                    new TestProject("10001", "PROJ", "My Project", "A description", "John Doe"),
                    new TestProject("10002", "TEST", "Test Project", "Another", "Jane Doe")
                )
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var projects = adapter.getAllProjects().get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(projects).hasSize(2);
            softly.assertThat(projects.get(0).key()).isEqualTo("PROJ");
            softly.assertThat(projects.get(0).name()).isEqualTo("My Project");
            softly.assertThat(projects.get(0).description()).isEqualTo("A description");
            softly.assertThat(projects.get(0).leadDisplayName()).isEqualTo("John Doe");
            softly.assertThat(projects.get(0).issueCount()).isEqualTo(0);
            softly.assertThat(projects.get(1).key()).isEqualTo("TEST");
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmptyList_When_NoProjects() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn("[]");

            var projects = adapter.getAllProjects().get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(projects).isEmpty();
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmptyList_When_ApiReturnsError() throws Exception {
            when(httpResponse.statusCode()).thenReturn(500);

            var projects = adapter.getAllProjects().get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(projects).isEmpty();
            softly.assertAll();
        }
    }

    @Nested
    class GetIssuesInProject {

        @Test
        void Should_ReturnIssues_When_ApiReturnsWikiMarkupDescription() throws Exception {
            var responseBody = createIssueSearchResponse(
                List.of(
                    new TestIssue("100", "PROJ-1", "PROJ", "Fix bug",
                        "The login page *fails* when using SSO", "Reporter1", "Assignee1", "Open", "Bug",
                        "2024-01-10T08:00:00.000+0000", "2024-01-15T12:00:00.000+0000")
                ), 1
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesInProject("PROJ").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues).hasSize(1);
            softly.assertThat(issues.get(0).key()).isEqualTo("PROJ-1");
            softly.assertThat(issues.get(0).summary()).isEqualTo("Fix bug");
            softly.assertThat(issues.get(0).descriptionText()).contains("The login page *fails* when using SSO");
            softly.assertThat(issues.get(0).projectKey()).isEqualTo("PROJ");
            softly.assertAll();
        }

        @Test
        void Should_ParseDcDateFormat_When_FieldsPresent() throws Exception {
            var responseBody = createIssueSearchResponse(
                List.of(
                    new TestIssue("200", "PROJ-2", "PROJ", "Feature",
                        "Body", "Alice", "Bob", "In Progress", "Story",
                        "2024-06-15T14:30:00.000+0200", "2024-06-16T09:00:00.000+0200")
                ), 1
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesInProject("PROJ").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues.get(0).metadata().created()).isNotNull();
            softly.assertThat(issues.get(0).metadata().updated()).isNotNull();
            softly.assertThat(issues.get(0).metadata().reporter()).isEqualTo("Alice");
            softly.assertThat(issues.get(0).metadata().assignee()).isEqualTo("Bob");
            softly.assertThat(issues.get(0).metadata().status()).isEqualTo("In Progress");
            softly.assertThat(issues.get(0).metadata().issueType()).isEqualTo("Story");
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmptyList_When_SearchFails() throws Exception {
            when(httpResponse.statusCode()).thenReturn(400);

            var issues = adapter.getIssuesInProject("PROJ").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues).isEmpty();
            softly.assertAll();
        }
    }

    @Nested
    class GetAllComments {

        @Test
        void Should_ReturnComments_When_BodyIsPlainString() throws Exception {
            var responseBody = createDcCommentsResponse(
                List.of(
                    new TestComment("c1", "Alice", "First comment with *wiki markup*",
                        "2024-01-01T10:00:00.000+0000", "2024-01-01T10:00:00.000+0000"),
                    new TestComment("c2", "Bob", "Second comment",
                        "2024-01-02T10:00:00.000+0000", "2024-01-02T10:00:00.000+0000")
                ), 2
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var comments = adapter.getAllComments("PROJ-1").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(comments).hasSize(2);
            softly.assertThat(comments.get(0).id()).isEqualTo("c1");
            softly.assertThat(comments.get(0).authorDisplayName()).isEqualTo("Alice");
            softly.assertThat(comments.get(0).bodyText()).isEqualTo("First comment with *wiki markup*");
            softly.assertThat(comments.get(0).created()).isNotNull();
            softly.assertThat(comments.get(1).id()).isEqualTo("c2");
            softly.assertAll();
        }

        @Test
        void Should_PaginateComments_When_MorePagesAvailable() throws Exception {
            var page1 = createDcCommentsResponse(
                List.of(new TestComment("c1", "Alice", "Comment 1", null, null)), 2
            );
            var page2 = createDcCommentsResponse(
                List.of(new TestComment("c2", "Bob", "Comment 2", null, null)), 2
            );

            var response1 = mock(HttpResponse.class);
            when(response1.statusCode()).thenReturn(200);
            when(response1.body()).thenReturn(page1);

            var response2 = mock(HttpResponse.class);
            when(response2.statusCode()).thenReturn(200);
            when(response2.body()).thenReturn(page2);

            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response1))
                .thenReturn(CompletableFuture.completedFuture(response2));

            var comments = adapter.getAllComments("PROJ-1").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(comments).hasSize(2);
            softly.assertThat(comments.get(0).id()).isEqualTo("c1");
            softly.assertThat(comments.get(1).id()).isEqualTo("c2");
            softly.assertAll();
        }
    }

    @Nested
    class BearerAuthEncoding {

        @Test
        void Should_SendBearerToken_When_CallingApi() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);

            adapter.testConnection().get();

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).sendAsync(requestCaptor.capture(), any(HttpResponse.BodyHandlers.ofString().getClass()));

            String authHeader = requestCaptor.getValue().headers().firstValue("Authorization").orElseThrow();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(authHeader).isEqualTo("Bearer test-password");
            softly.assertAll();
        }
    }

    @Nested
    class GetAttachments {

        @Test
        void Should_ReturnAttachments_When_IssueHasAttachments() throws Exception {
            var responseBody = createIssueWithAttachmentsResponse(
                "PROJ-1",
                List.of(
                    new TestAttachment("att1", "file.pdf", "application/pdf", 1024,
                        "https://jira.softcom.pro/secure/attachment/10001/file.pdf", "Alice"),
                    new TestAttachment("att2", "image.png", "image/png", 2048,
                        "https://jira.softcom.pro/secure/attachment/10002/image.png", "Bob")
                )
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var attachments = adapter.getAttachments("PROJ-1").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(attachments).hasSize(2);
            softly.assertThat(attachments.get(0).id()).isEqualTo("att1");
            softly.assertThat(attachments.get(0).filename()).isEqualTo("file.pdf");
            softly.assertThat(attachments.get(0).contentUrl())
                .isEqualTo("https://jira.softcom.pro/secure/attachment/10001/file.pdf");
            softly.assertThat(attachments.get(1).id()).isEqualTo("att2");
            softly.assertAll();
        }
    }

    @Nested
    class RetryPolicy {

        @Test
        void Should_RetryAndSucceed_When_FirstAttemptReturns500() throws Exception {
            var r500 = mock(HttpResponse.class);
            when(r500.statusCode()).thenReturn(500);

            var r200 = mock(HttpResponse.class);
            when(r200.statusCode()).thenReturn(200);

            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(r500))
                .thenReturn(CompletableFuture.completedFuture(r200));

            var result = adapter.testConnection().get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result).isTrue();
            softly.assertAll();
        }
    }

    // --- Test data records ---

    private record TestProject(String id, String key, String name, String description, String lead) {}
    private record TestIssue(String id, String key, String projectKey, String summary,
                             String description, String reporter, String assignee,
                             String status, String issueType, String created, String updated) {}
    private record TestComment(String id, String author, String bodyText, String created, String updated) {}
    private record TestAttachment(String id, String filename, String mimeType, long size,
                                  String contentUrl, String author) {}

    // --- JSON builders ---

    private String createDcProjectsResponse(List<TestProject> projects) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (var p : projects) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("id", p.id());
            node.put("key", p.key());
            node.put("name", p.name());
            node.put("description", p.description());

            ObjectNode lead = JsonNodeFactory.instance.objectNode();
            lead.put("displayName", p.lead());
            node.set("lead", lead);

            array.add(node);
        }
        return array.toString();
    }

    private String createIssueSearchResponse(List<TestIssue> issues, int total) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode issuesArray = JsonNodeFactory.instance.arrayNode();

        for (var issue : issues) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("id", issue.id());
            node.put("key", issue.key());

            ObjectNode fields = JsonNodeFactory.instance.objectNode();
            fields.put("summary", issue.summary());
            fields.put("description", issue.description());

            ObjectNode project = JsonNodeFactory.instance.objectNode();
            project.put("key", issue.projectKey());
            fields.set("project", project);

            if (issue.reporter() != null) {
                ObjectNode reporter = JsonNodeFactory.instance.objectNode();
                reporter.put("displayName", issue.reporter());
                fields.set("reporter", reporter);
            }
            if (issue.assignee() != null) {
                ObjectNode assignee = JsonNodeFactory.instance.objectNode();
                assignee.put("displayName", issue.assignee());
                fields.set("assignee", assignee);
            }
            if (issue.status() != null) {
                ObjectNode status = JsonNodeFactory.instance.objectNode();
                status.put("name", issue.status());
                fields.set("status", status);
            }
            if (issue.issueType() != null) {
                ObjectNode issueType = JsonNodeFactory.instance.objectNode();
                issueType.put("name", issue.issueType());
                fields.set("issuetype", issueType);
            }
            if (issue.created() != null) {
                fields.put("created", issue.created());
            }
            if (issue.updated() != null) {
                fields.put("updated", issue.updated());
            }

            node.set("fields", fields);
            issuesArray.add(node);
        }

        root.set("issues", issuesArray);
        root.put("total", total);
        return root.toString();
    }

    private String createDcCommentsResponse(List<TestComment> comments, int total) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode commentsArray = JsonNodeFactory.instance.arrayNode();

        for (var c : comments) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("id", c.id());
            node.put("body", c.bodyText());

            ObjectNode author = JsonNodeFactory.instance.objectNode();
            author.put("displayName", c.author());
            node.set("author", author);

            if (c.created() != null) node.put("created", c.created());
            if (c.updated() != null) node.put("updated", c.updated());

            commentsArray.add(node);
        }

        root.set("comments", commentsArray);
        root.put("total", total);
        return root.toString();
    }

    private String createIssueWithAttachmentsResponse(String issueKey, List<TestAttachment> attachments) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode issuesArray = JsonNodeFactory.instance.arrayNode();

        ObjectNode issueNode = JsonNodeFactory.instance.objectNode();
        issueNode.put("key", issueKey);

        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        ArrayNode attachmentArray = JsonNodeFactory.instance.arrayNode();

        for (var att : attachments) {
            ObjectNode attNode = JsonNodeFactory.instance.objectNode();
            attNode.put("id", att.id());
            attNode.put("filename", att.filename());
            attNode.put("mimeType", att.mimeType());
            attNode.put("size", att.size());
            attNode.put("content", att.contentUrl());

            ObjectNode author = JsonNodeFactory.instance.objectNode();
            author.put("displayName", att.author());
            attNode.set("author", author);

            attachmentArray.add(attNode);
        }

        fields.set("attachment", attachmentArray);
        issueNode.set("fields", fields);
        issuesArray.add(issueNode);

        root.set("issues", issuesArray);
        root.put("total", 1);
        return root.toString();
    }
}
