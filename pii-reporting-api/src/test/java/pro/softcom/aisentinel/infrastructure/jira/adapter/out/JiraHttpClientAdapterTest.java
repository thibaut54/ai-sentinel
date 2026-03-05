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
import pro.softcom.aisentinel.application.jira.service.AdfContentParser;
import pro.softcom.aisentinel.domain.jira.JiraAttachmentInfo;
import pro.softcom.aisentinel.domain.jira.JiraComment;
import pro.softcom.aisentinel.domain.jira.JiraIssue;
import pro.softcom.aisentinel.domain.jira.JiraProject;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for JiraHttpClientAdapter.
 * Tests cover: connection, project retrieval, issue search, comments pagination,
 * attachments, auth encoding, and retry policy.
 */
@ExtendWith(MockitoExtension.class)
class JiraHttpClientAdapterTest {

    @Mock
    private JiraConnectionConfig config;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private final AdfContentParser adfParser = new AdfContentParser();
    private JiraHttpClientAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        setupConfig();
        createAdapterWithMockedHttpClient();
    }

    private void setupConfig() {
        lenient().when(config.baseUrl()).thenReturn("https://jira.test.atlassian.net");
        lenient().when(config.email()).thenReturn("user@test.com");
        lenient().when(config.apiToken()).thenReturn("test-api-token");
        lenient().when(config.getRestApiUrl()).thenReturn("https://jira.test.atlassian.net/rest/api/3");
        lenient().when(config.connectTimeout()).thenReturn(5000);
        lenient().when(config.readTimeout()).thenReturn(10000);
        lenient().when(config.maxRetries()).thenReturn(3);
        lenient().when(config.issuesLimit()).thenReturn(50);
        lenient().when(config.maxIssues()).thenReturn(5000);
    }

    private void createAdapterWithMockedHttpClient() throws Exception {
        var objectMapper = new ObjectMapper();
        adapter = new JiraHttpClientAdapter(config, objectMapper, adfParser);

        Field retryExecutorField = JiraHttpClientAdapter.class.getDeclaredField("retryExecutor");
        retryExecutorField.setAccessible(true);
        Object retryExecutor = retryExecutorField.get(adapter);

        Field httpClientField = retryExecutor.getClass().getDeclaredField("httpClient");
        httpClientField.setAccessible(true);
        httpClientField.set(retryExecutor, httpClient);

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
    }

    @Nested
    class GetAllProjects {

        @Test
        void Should_ReturnProjects_When_ApiReturnsValidData() throws Exception {
            var responseBody = createProjectSearchResponse(
                List.of(
                    new TestProject("10001", "PROJ", "My Project", "A description", "John Doe", 42),
                    new TestProject("10002", "TEST", "Test Project", "Another", "Jane Doe", 10)
                ), 2
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
            softly.assertThat(projects.get(0).issueCount()).isEqualTo(42);
            softly.assertThat(projects.get(1).key()).isEqualTo("TEST");
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmptyList_When_NoProjects() throws Exception {
            var responseBody = createProjectSearchResponse(List.of(), 0);
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

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

        @Test
        void Should_PaginateProjects_When_MorePagesAvailable() throws Exception {
            var page1 = createProjectSearchResponse(
                List.of(new TestProject("1", "P1", "Project 1", "", "Lead", 5)), 2
            );
            var page2 = createProjectSearchResponse(
                List.of(new TestProject("2", "P2", "Project 2", "", "Lead", 3)), 2
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

            var projects = adapter.getAllProjects().get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(projects).hasSize(2);
            softly.assertThat(projects.get(0).key()).isEqualTo("P1");
            softly.assertThat(projects.get(1).key()).isEqualTo("P2");
            softly.assertAll();
        }
    }

    @Nested
    class GetIssuesInProject {

        @Test
        void Should_ReturnIssues_When_ApiReturnsValidData() throws Exception {
            var responseBody = createIssueSearchResponse(
                List.of(
                    new TestIssue("100", "PROJ-1", "PROJ", "Fix bug",
                        createAdfText("Description text"), "Reporter1", "Assignee1", "Open", "Bug")
                ), 1
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesInProject("PROJ").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues).hasSize(1);
            softly.assertThat(issues.get(0).key()).isEqualTo("PROJ-1");
            softly.assertThat(issues.get(0).summary()).isEqualTo("Fix bug");
            softly.assertThat(issues.get(0).descriptionText()).contains("Description text");
            softly.assertThat(issues.get(0).projectKey()).isEqualTo("PROJ");
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmptyList_When_NoIssues() throws Exception {
            var responseBody = createIssueSearchResponse(List.of(), 0);
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesInProject("PROJ").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues).isEmpty();
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

        @Test
        void Should_ParseIssueMetadata_When_FieldsPresent() throws Exception {
            var responseBody = createIssueSearchResponse(
                List.of(
                    new TestIssue("200", "PROJ-2", "PROJ", "Feature",
                        createAdfText("Body"), "Alice", "Bob", "In Progress", "Story")
                ), 1
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesInProject("PROJ").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues.get(0).metadata().reporter()).isEqualTo("Alice");
            softly.assertThat(issues.get(0).metadata().assignee()).isEqualTo("Bob");
            softly.assertThat(issues.get(0).metadata().status()).isEqualTo("In Progress");
            softly.assertThat(issues.get(0).metadata().issueType()).isEqualTo("Story");
            softly.assertAll();
        }
    }

    @Nested
    class GetIssuesUpdatedSince {

        @Test
        void Should_ReturnIssues_When_UpdatedSinceGiven() throws Exception {
            var responseBody = createIssueSearchResponse(
                List.of(
                    new TestIssue("300", "PROJ-3", "PROJ", "Recent change",
                        createAdfText("Updated"), "R", "A", "Done", "Task")
                ), 1
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesUpdatedSince("PROJ", Instant.parse("2024-01-15T10:30:00Z")).get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues).hasSize(1);
            softly.assertThat(issues.get(0).key()).isEqualTo("PROJ-3");
            softly.assertAll();
        }
    }

    @Nested
    class GetAllComments {

        @Test
        void Should_ReturnComments_When_CommentsExist() throws Exception {
            var responseBody = createCommentsResponse(
                List.of(
                    new TestComment("c1", "Alice", "First comment"),
                    new TestComment("c2", "Bob", "Second comment")
                ), 2
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var comments = adapter.getAllComments("PROJ-1").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(comments).hasSize(2);
            softly.assertThat(comments.get(0).id()).isEqualTo("c1");
            softly.assertThat(comments.get(0).authorDisplayName()).isEqualTo("Alice");
            softly.assertThat(comments.get(0).bodyText()).contains("First comment");
            softly.assertThat(comments.get(1).id()).isEqualTo("c2");
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmptyList_When_NoComments() throws Exception {
            var responseBody = createCommentsResponse(List.of(), 0);
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var comments = adapter.getAllComments("PROJ-1").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(comments).isEmpty();
            softly.assertAll();
        }

        @Test
        void Should_PaginateComments_When_MorePagesAvailable() throws Exception {
            var page1 = createCommentsResponse(
                List.of(new TestComment("c1", "Alice", "Comment 1")), 2
            );
            var page2 = createCommentsResponse(
                List.of(new TestComment("c2", "Bob", "Comment 2")), 2
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

        @Test
        void Should_ReturnEmptyList_When_CommentsFetchFails() throws Exception {
            when(httpResponse.statusCode()).thenReturn(404);

            var comments = adapter.getAllComments("PROJ-1").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(comments).isEmpty();
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
                    new TestAttachment("att1", "file.pdf", "application/pdf", 1024, "https://jira.test/att/1", "Alice"),
                    new TestAttachment("att2", "image.png", "image/png", 2048, "https://jira.test/att/2", "Bob")
                )
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var attachments = adapter.getAttachments("PROJ-1").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(attachments).hasSize(2);
            softly.assertThat(attachments.get(0).id()).isEqualTo("att1");
            softly.assertThat(attachments.get(0).filename()).isEqualTo("file.pdf");
            softly.assertThat(attachments.get(0).mimeType()).isEqualTo("application/pdf");
            softly.assertThat(attachments.get(0).size()).isEqualTo(1024);
            softly.assertThat(attachments.get(1).id()).isEqualTo("att2");
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmptyList_When_NoAttachments() throws Exception {
            var responseBody = createIssueWithAttachmentsResponse("PROJ-1", List.of());
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var attachments = adapter.getAttachments("PROJ-1").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(attachments).isEmpty();
            softly.assertAll();
        }
    }

    @Nested
    class BasicAuthEncoding {

        @Test
        void Should_SendCorrectAuthHeader_When_CallingApi() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);

            adapter.testConnection().get();

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).sendAsync(requestCaptor.capture(), any(HttpResponse.BodyHandlers.ofString().getClass()));

            HttpRequest request = requestCaptor.getValue();
            String authHeader = request.headers().firstValue("Authorization").orElseThrow();

            String expectedCredentials = "user@test.com:test-api-token";
            String expectedEncoded = Base64.getEncoder()
                .encodeToString(expectedCredentials.getBytes(StandardCharsets.UTF_8));

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(authHeader).isEqualTo("Basic " + expectedEncoded);
            softly.assertAll();
        }

        @Test
        void Should_TrimCredentials_When_WhitespaceIsPresent() throws Exception {
            when(config.email()).thenReturn("  user@test.com  ");
            when(config.apiToken()).thenReturn("  test-api-token  ");
            createAdapterWithMockedHttpClient();

            when(httpResponse.statusCode()).thenReturn(200);

            adapter.testConnection().get();

            ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).sendAsync(requestCaptor.capture(), any(HttpResponse.BodyHandlers.ofString().getClass()));

            String authHeader = requestCaptor.getValue().headers().firstValue("Authorization").orElseThrow();

            String expectedCredentials = "user@test.com:test-api-token";
            String expectedEncoded = Base64.getEncoder()
                .encodeToString(expectedCredentials.getBytes(StandardCharsets.UTF_8));

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(authHeader).isEqualTo("Basic " + expectedEncoded);
            softly.assertAll();
        }
    }

    @Nested
    class RetryPolicy {

        @Test
        void Should_RetryAndSucceed_When_FirstAttemptReturns500() throws Exception {
            lenient().when(config.maxRetries()).thenReturn(1);
            createAdapterWithMockedHttpClient();

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

        @Test
        void Should_RetryAndSucceed_When_FirstAttemptReturns429() throws Exception {
            lenient().when(config.maxRetries()).thenReturn(1);
            createAdapterWithMockedHttpClient();

            var r429 = mock(HttpResponse.class);
            when(r429.statusCode()).thenReturn(429);

            var r200 = mock(HttpResponse.class);
            when(r200.statusCode()).thenReturn(200);

            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(r429))
                .thenReturn(CompletableFuture.completedFuture(r200));

            var result = adapter.testConnection().get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result).isTrue();
            softly.assertAll();
        }
    }

    // --- Test data records ---

    private record TestProject(String id, String key, String name, String description, String lead, int issueCount) {}
    private record TestIssue(String id, String key, String projectKey, String summary,
                             String descriptionAdf, String reporter, String assignee,
                             String status, String issueType) {}
    private record TestComment(String id, String author, String bodyText) {}
    private record TestAttachment(String id, String filename, String mimeType, long size,
                                  String contentUrl, String author) {}

    // --- JSON builders ---

    private String createProjectSearchResponse(List<TestProject> projects, int total) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode values = JsonNodeFactory.instance.arrayNode();

        for (var p : projects) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("id", p.id());
            node.put("key", p.key());
            node.put("name", p.name());
            node.put("description", p.description());

            ObjectNode lead = JsonNodeFactory.instance.objectNode();
            lead.put("displayName", p.lead());
            node.set("lead", lead);

            ObjectNode insight = JsonNodeFactory.instance.objectNode();
            insight.put("totalIssueCount", p.issueCount());
            node.set("insight", insight);

            values.add(node);
        }

        root.set("values", values);
        root.put("total", total);
        return root.toString();
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

            ObjectNode project = JsonNodeFactory.instance.objectNode();
            project.put("key", issue.projectKey());
            fields.set("project", project);

            if (issue.descriptionAdf() != null) {
                try {
                    fields.set("description", new ObjectMapper().readTree(issue.descriptionAdf()));
                } catch (Exception _) {
                    fields.putNull("description");
                }
            }

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

            node.set("fields", fields);
            issuesArray.add(node);
        }

        root.set("issues", issuesArray);
        root.put("total", total);
        return root.toString();
    }

    private String createCommentsResponse(List<TestComment> comments, int total) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode commentsArray = JsonNodeFactory.instance.arrayNode();

        for (var c : comments) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("id", c.id());

            ObjectNode author = JsonNodeFactory.instance.objectNode();
            author.put("displayName", c.author());
            node.set("author", author);

            try {
                node.set("body", new ObjectMapper().readTree(createAdfText(c.bodyText())));
            } catch (Exception _) {
                node.putNull("body");
            }

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

    private static String createAdfText(String text) {
        return """
            {"type":"doc","version":1,"content":[{"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}
            """.formatted(text).trim();
    }
}
