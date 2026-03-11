package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
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
import pro.softcom.aisentinel.domain.jira.JiraProject;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.http.HttpRetryExecutor;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AbstractJiraHttpClientAdapter.
 * Uses a minimal concrete subclass (TestableAdapter) to test the shared logic.
 */
@ExtendWith(MockitoExtension.class)
class AbstractJiraHttpClientAdapterTest {

    @Mock
    private JiraConnectionConfig config;

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private TestableAdapter adapter;

    @BeforeEach
    void setUp() {
        setupConfig();
        createAdapter();
    }

    private void setupConfig() {
        lenient().when(config.baseUrl()).thenReturn("https://jira.test.example.com");
        lenient().when(config.email()).thenReturn("user@test.com");
        lenient().when(config.apiToken()).thenReturn("test-token");
        lenient().when(config.getRestApiUrl()).thenReturn("https://jira.test.example.com/rest/api/3");
        lenient().when(config.connectTimeout()).thenReturn(5000);
        lenient().when(config.readTimeout()).thenReturn(10000);
        lenient().when(config.maxRetries()).thenReturn(3);
        lenient().when(config.issuesLimit()).thenReturn(50);
        lenient().when(config.maxIssues()).thenReturn(5000);

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

    private void createAdapter() {
        var objectMapper = new ObjectMapper();
        var retryExecutor = new HttpRetryExecutor(httpClient, config.maxRetries());
        adapter = new TestableAdapter(config, objectMapper, retryExecutor);

        lenient().when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenReturn(CompletableFuture.completedFuture(httpResponse));
    }

    @Nested
    class TestConnection {

        @Test
        void Should_ReturnTrue_When_ServerResponds200() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);

            var result = adapter.testConnection().get();

            assertThat(result).isTrue();
        }

        @Test
        void Should_ReturnFalse_When_ServerResponds401() throws Exception {
            when(httpResponse.statusCode()).thenReturn(401);

            var result = adapter.testConnection().get();

            assertThat(result).isFalse();
        }

        @Test
        void Should_ReturnFalse_When_ExceptionOccurs() throws Exception {
            when(httpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandlers.ofString().getClass())))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Connection refused")));

            var result = adapter.testConnection().get();

            assertThat(result).isFalse();
        }

        @Test
        void Should_UseMyselfPath_When_BuildingConnectionTestUri() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);

            adapter.testConnection().get();

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).sendAsync(captor.capture(), any(HttpResponse.BodyHandlers.ofString().getClass()));

            assertThat(captor.getValue().uri()).hasToString("https://jira.test.example.com/rest/api/3/myself");
        }

        @Test
        void Should_IncludeAuthHeader_When_SendingRequest() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);

            adapter.testConnection().get();

            ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
            verify(httpClient).sendAsync(captor.capture(), any(HttpResponse.BodyHandlers.ofString().getClass()));

            var authHeader = captor.getValue().headers().firstValue("Authorization").orElseThrow();
            assertThat(authHeader).isEqualTo("TestAuth test-token");
        }
    }

    @Nested
    class ValidateProjectKey {

        @Test
        void Should_ThrowException_When_ProjectKeyIsNull() {
            assertThatThrownBy(() -> adapter.getIssuesInProject(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Jira project key");
        }

        @Test
        void Should_ThrowException_When_ProjectKeyIsInvalid() {
            assertThatThrownBy(() -> adapter.getIssuesInProject("lowercase"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Jira project key");
        }

        @Test
        void Should_ThrowException_When_ProjectKeyStartsWithDigit() {
            assertThatThrownBy(() -> adapter.getIssuesInProject("1PROJ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Jira project key");
        }

        @Test
        void Should_Accept_When_ProjectKeyIsValid() throws Exception {
            var responseBody = createEmptyIssueSearchResponse();
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesInProject("PROJ").get();

            assertThat(issues).isEmpty();
        }

        @Test
        void Should_Accept_When_ProjectKeyContainsUnderscore() throws Exception {
            var responseBody = createEmptyIssueSearchResponse();
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesInProject("MY_PROJECT").get();

            assertThat(issues).isEmpty();
        }
    }

    @Nested
    class GetIssuesInProject {

        @Test
        void Should_ReturnIssues_When_ApiReturnsValidData() throws Exception {
            var responseBody = createIssueSearchResponse(
                List.of(new TestIssue("100", "PROJ-1", "PROJ", "Fix bug", "Description text")),
                1
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesInProject("PROJ").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues).hasSize(1);
            softly.assertThat(issues.get(0).key()).isEqualTo("PROJ-1");
            softly.assertThat(issues.get(0).summary()).isEqualTo("Fix bug");
            softly.assertThat(issues.get(0).descriptionText()).isEqualTo("Description text");
            softly.assertThat(issues.get(0).projectKey()).isEqualTo("PROJ");
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmptyList_When_SearchFails() throws Exception {
            when(httpResponse.statusCode()).thenReturn(500);

            var issues = adapter.getIssuesInProject("PROJ").get();

            assertThat(issues).isEmpty();
        }

        @Test
        void Should_ReturnEmptyList_When_NoIssuesFound() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(createEmptyIssueSearchResponse());

            var issues = adapter.getIssuesInProject("PROJ").get();

            assertThat(issues).isEmpty();
        }

        @Test
        void Should_HandleMissingFieldsNode_When_IssueHasNoFields() throws Exception {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            ArrayNode issuesArray = JsonNodeFactory.instance.arrayNode();
            ObjectNode issue = JsonNodeFactory.instance.objectNode();
            issue.put("id", "1");
            issue.put("key", "PROJ-1");
            // No "fields" node
            issuesArray.add(issue);
            root.set("issues", issuesArray);
            root.put("total", 1);

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(root.toString());

            var issues = adapter.getIssuesInProject("PROJ").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues).hasSize(1);
            softly.assertThat(issues.get(0).id()).isEqualTo("1");
            softly.assertThat(issues.get(0).key()).isEqualTo("PROJ-1");
            softly.assertAll();
        }

        @Test
        void Should_RespectMaxIssuesLimit_When_TooManyIssuesExist() throws Exception {
            lenient().when(config.maxIssues()).thenReturn(1);
            createAdapter();

            var responseBody = createIssueSearchResponse(
                List.of(
                    new TestIssue("1", "PROJ-1", "PROJ", "Issue 1", "Desc 1"),
                    new TestIssue("2", "PROJ-2", "PROJ", "Issue 2", "Desc 2")
                ), 10
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesInProject("PROJ").get();

            // Should stop after first batch even though total=10 because maxIssues=1
            assertThat(issues).hasSize(2); // batch of 2 returned, then limit checked
        }
    }

    @Nested
    class GetIssuesUpdatedSince {

        @Test
        void Should_ReturnIssues_When_UpdatesExistSinceDate() throws Exception {
            var responseBody = createIssueSearchResponse(
                List.of(new TestIssue("300", "PROJ-3", "PROJ", "Recent change", "Updated desc")),
                1
            );
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var issues = adapter.getIssuesUpdatedSince("PROJ", Instant.parse("2024-01-15T10:30:00Z")).get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues).hasSize(1);
            softly.assertThat(issues.get(0).key()).isEqualTo("PROJ-3");
            softly.assertAll();
        }

        @Test
        void Should_ThrowException_When_ProjectKeyIsInvalid() {
            final Instant now = Instant.now();
            assertThatThrownBy(() -> adapter.getIssuesUpdatedSince("bad-key", now))
                .isInstanceOf(IllegalArgumentException.class);
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
            softly.assertThat(comments.get(1).id()).isEqualTo("c2");
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmptyList_When_NoComments() throws Exception {
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(createCommentsResponse(List.of(), 0));

            var comments = adapter.getAllComments("PROJ-1").get();

            assertThat(comments).isEmpty();
        }

        @Test
        void Should_ReturnEmptyList_When_CommentsFetchFails() throws Exception {
            when(httpResponse.statusCode()).thenReturn(404);

            var comments = adapter.getAllComments("PROJ-1").get();

            assertThat(comments).isEmpty();
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
    }

    @Nested
    class GetAttachments {

        @Test
        void Should_ReturnAttachments_When_IssueHasAttachments() throws Exception {
            var responseBody = createIssueWithAttachmentsResponse(
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
            var responseBody = createIssueWithAttachmentsResponse(List.of());
            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(responseBody);

            var attachments = adapter.getAttachments("PROJ-1").get();

            assertThat(attachments).isEmpty();
        }

        @Test
        void Should_ReturnEmptyList_When_ApiReturnsError() throws Exception {
            when(httpResponse.statusCode()).thenReturn(500);

            var attachments = adapter.getAttachments("PROJ-1").get();

            assertThat(attachments).isEmpty();
        }
    }

    @Nested
    class ParseSingleProject {

        @Test
        void Should_ParseProjectWithInsightCount_When_HasInsightIsTrue() {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("id", "10001");
            node.put("key", "PROJ");
            node.put("name", "My Project");
            node.put("description", "A test project");

            ObjectNode lead = JsonNodeFactory.instance.objectNode();
            lead.put("displayName", "Lead User");
            node.set("lead", lead);

            ObjectNode insight = JsonNodeFactory.instance.objectNode();
            insight.put("totalIssueCount", 42);
            node.set("insight", insight);

            JiraProject project = adapter.parseSingleProject(node, true);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(project.key()).isEqualTo("PROJ");
            softly.assertThat(project.name()).isEqualTo("My Project");
            softly.assertThat(project.leadDisplayName()).isEqualTo("Lead User");
            softly.assertThat(project.issueCount()).isEqualTo(42);
            softly.assertThat(project.url()).contains("/projects/PROJ");
            softly.assertAll();
        }

        @Test
        void Should_ParseProjectWithZeroCount_When_HasInsightIsFalse() {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("id", "10002");
            node.put("key", "DC");
            node.put("name", "Data Center Project");

            ObjectNode lead = JsonNodeFactory.instance.objectNode();
            lead.put("displayName", "DC Lead");
            node.set("lead", lead);

            JiraProject project = adapter.parseSingleProject(node, false);

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(project.key()).isEqualTo("DC");
            softly.assertThat(project.issueCount()).isZero();
            softly.assertAll();
        }

        @Test
        void Should_HandleMissingLead_When_LeadNodeAbsent() {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("id", "10003");
            node.put("key", "NOLEAD");
            node.put("name", "No Lead Project");

            JiraProject project = adapter.parseSingleProject(node, false);

            assertThat(project.leadDisplayName()).isEmpty();
        }

        @Test
        void Should_BuildEmptyUrl_When_BaseUrlIsBlank() {
            lenient().when(config.baseUrl()).thenReturn("");
            createAdapter();

            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("id", "1");
            node.put("key", "TEST");
            node.put("name", "Test");

            JiraProject project = adapter.parseSingleProject(node, false);

            assertThat(project.url()).isEmpty();
        }
    }

    @Nested
    class BuildSearchBody {

        @Test
        void Should_BuildValidJson_When_FieldsProvided() {
            var body = adapter.buildSearchBody("project = PROJ", 0, 50, List.of("summary", "description"));

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(body).contains("\"jql\"");
            softly.assertThat(body).contains("project = PROJ");
            softly.assertThat(body).contains("\"startAt\":0");
            softly.assertThat(body).contains("\"maxResults\":50");
            softly.assertThat(body).contains("summary");
            softly.assertThat(body).contains("description");
            softly.assertAll();
        }
    }

    @Nested
    class IssueMetadataParsing {

        @Test
        void Should_ParseMetadata_When_AllFieldsPresent() throws Exception {
            var responseBody = createIssueSearchResponseWithMetadata(
                "200", "PROJ-2", "PROJ", "Feature", "Body",
                "Alice", "Bob", "In Progress", "Story"
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

        @Test
        void Should_HandleNullMetadataFields_When_FieldsMissing() throws Exception {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            ArrayNode issuesArray = JsonNodeFactory.instance.arrayNode();
            ObjectNode issue = JsonNodeFactory.instance.objectNode();
            issue.put("id", "1");
            issue.put("key", "PROJ-1");
            ObjectNode fields = JsonNodeFactory.instance.objectNode();
            fields.put("summary", "Test");
            ObjectNode project = JsonNodeFactory.instance.objectNode();
            project.put("key", "PROJ");
            fields.set("project", project);
            // No reporter, assignee, status, issuetype
            issue.set("fields", fields);
            issuesArray.add(issue);
            root.set("issues", issuesArray);
            root.put("total", 1);

            when(httpResponse.statusCode()).thenReturn(200);
            when(httpResponse.body()).thenReturn(root.toString());

            var issues = adapter.getIssuesInProject("PROJ").get();

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(issues.get(0).metadata().reporter()).isNull();
            softly.assertThat(issues.get(0).metadata().assignee()).isNull();
            softly.assertThat(issues.get(0).metadata().status()).isNull();
            softly.assertThat(issues.get(0).metadata().issueType()).isNull();
            softly.assertAll();
        }
    }

    // --- Testable concrete implementation ---

    /**
     * Minimal concrete subclass of AbstractJiraHttpClientAdapter for testing shared logic.
     * Implements template methods with simple passthrough behavior.
     */
    static class TestableAdapter extends AbstractJiraHttpClientAdapter {

        TestableAdapter(JiraConnectionConfig config, ObjectMapper objectMapper, HttpRetryExecutor retryExecutor) {
            super(config, objectMapper, retryExecutor);
        }

        @Override
        protected String getAuthHeader() {
            return "TestAuth " + config.apiToken();
        }

        @Override
        protected String parseTextContent(JsonNode node) {
            if (node == null || node.isNull()) {
                return "";
            }
            return node.asText("");
        }

        @Override
        protected Instant parseDate(JsonNode fieldsOrNode, String fieldName) {
            if (!fieldsOrNode.has(fieldName) || fieldsOrNode.get(fieldName).isNull()) {
                return null;
            }
            try {
                return Instant.parse(fieldsOrNode.get(fieldName).asText());
            } catch (Exception _) {
                return null;
            }
        }

        @Override
        public CompletableFuture<List<JiraProject>> getAllProjects() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<byte[]> getAttachmentContent(String attachmentId) {
            return CompletableFuture.completedFuture(new byte[0]);
        }
    }

    // --- Test data records ---

    private record TestIssue(String id, String key, String projectKey, String summary, String description) {}
    private record TestComment(String id, String author, String bodyText) {}
    private record TestAttachment(String id, String filename, String mimeType, long size,
                                  String contentUrl, String author) {}

    // --- JSON builders ---

    private String createEmptyIssueSearchResponse() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.set("issues", JsonNodeFactory.instance.arrayNode());
        root.put("total", 0);
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
            fields.put("description", issue.description());

            ObjectNode project = JsonNodeFactory.instance.objectNode();
            project.put("key", issue.projectKey());
            fields.set("project", project);

            node.set("fields", fields);
            issuesArray.add(node);
        }

        root.set("issues", issuesArray);
        root.put("total", total);
        return root.toString();
    }

    private String createIssueSearchResponseWithMetadata(
        String id, String key, String projectKey, String summary, String description,
        String reporter, String assignee, String status, String issueType
    ) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode issuesArray = JsonNodeFactory.instance.arrayNode();

        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", id);
        node.put("key", key);

        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.put("summary", summary);
        fields.put("description", description);

        ObjectNode project = JsonNodeFactory.instance.objectNode();
        project.put("key", projectKey);
        fields.set("project", project);

        if (reporter != null) {
            ObjectNode reporterNode = JsonNodeFactory.instance.objectNode();
            reporterNode.put("displayName", reporter);
            fields.set("reporter", reporterNode);
        }
        if (assignee != null) {
            ObjectNode assigneeNode = JsonNodeFactory.instance.objectNode();
            assigneeNode.put("displayName", assignee);
            fields.set("assignee", assigneeNode);
        }
        if (status != null) {
            ObjectNode statusNode = JsonNodeFactory.instance.objectNode();
            statusNode.put("name", status);
            fields.set("status", statusNode);
        }
        if (issueType != null) {
            ObjectNode issueTypeNode = JsonNodeFactory.instance.objectNode();
            issueTypeNode.put("name", issueType);
            fields.set("issuetype", issueTypeNode);
        }

        node.set("fields", fields);
        issuesArray.add(node);
        root.set("issues", issuesArray);
        root.put("total", 1);
        return root.toString();
    }

    private String createCommentsResponse(List<TestComment> comments, int total) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode commentsArray = JsonNodeFactory.instance.arrayNode();

        for (var c : comments) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("id", c.id());
            node.put("body", c.bodyText());

            ObjectNode author = JsonNodeFactory.instance.objectNode();
            author.put("displayName", c.author());
            node.set("author", author);

            commentsArray.add(node);
        }

        root.set("comments", commentsArray);
        root.put("total", total);
        return root.toString();
    }

    private String createIssueWithAttachmentsResponse(List<TestAttachment> attachments) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        ArrayNode issuesArray = JsonNodeFactory.instance.arrayNode();

        ObjectNode issueNode = JsonNodeFactory.instance.objectNode();
        issueNode.put("key", "PROJ-1");

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
