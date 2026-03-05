package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import pro.softcom.aisentinel.application.jira.port.out.JiraClient;
import pro.softcom.aisentinel.application.jira.service.AdfContentParser;
import pro.softcom.aisentinel.domain.jira.JiraAttachmentInfo;
import pro.softcom.aisentinel.domain.jira.JiraComment;
import pro.softcom.aisentinel.domain.jira.JiraIssue;
import pro.softcom.aisentinel.domain.jira.JiraProject;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.http.HttpRetryExecutor;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * HTTP Adapter for Jira Cloud REST API v3.
 * Implements the JiraClient port using java.net.http.HttpClient with HTTP/2 and virtual threads.
 */
@Service
@Slf4j
public class JiraHttpClientAdapter implements JiraClient {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final DateTimeFormatter JQL_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("UTC"));

    private static final List<String> ISSUE_FIELDS = List.of(
        "summary", "description", "comment", "attachment",
        "created", "updated", "reporter", "assignee", "status", "issuetype"
    );

    private final JiraConnectionConfig config;
    private final ObjectMapper objectMapper;
    private final AdfContentParser adfParser;
    private final HttpRetryExecutor retryExecutor;

    public JiraHttpClientAdapter(
        @Qualifier("jiraConfig") JiraConnectionConfig config,
        ObjectMapper objectMapper,
        AdfContentParser adfParser
    ) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.adfParser = adfParser;
        this.retryExecutor = new HttpRetryExecutor(buildHttpClient(), config.maxRetries());
    }

    @Override
    public CompletableFuture<Boolean> testConnection() {
        log.info("Testing connection to Jira");
        var uri = URI.create(config.getRestApiUrl() + "/myself");
        var request = buildGetRequest(uri);
        return retryExecutor.executeRequest(request)
            .thenApply(response -> response.statusCode() == 200)
            .exceptionally(ex -> {
                log.error("Jira connection test failed", ex);
                return false;
            });
    }

    @Override
    public CompletableFuture<List<JiraProject>> getAllProjects() {
        log.info("Retrieving all Jira projects");
        return collectAllProjectsRecursively(0, new ArrayList<>());
    }

    @Override
    public CompletableFuture<List<JiraIssue>> getIssuesInProject(String projectKey) {
        log.info("Retrieving issues for project: {}", projectKey);
        var jql = "project = " + projectKey + " ORDER BY updated DESC";
        return collectAllIssuesRecursively(jql, 0, new ArrayList<>());
    }

    @Override
    public CompletableFuture<List<JiraIssue>> getIssuesUpdatedSince(String projectKey, Instant since) {
        log.info("Retrieving issues updated since {} for project: {}", since, projectKey);
        var sinceFormatted = JQL_DATE_FORMATTER.format(since);
        var jql = "project = " + projectKey + " AND updated >= '" + sinceFormatted + "' ORDER BY updated DESC";
        return collectAllIssuesRecursively(jql, 0, new ArrayList<>());
    }

    @Override
    public CompletableFuture<List<JiraComment>> getAllComments(String issueKey) {
        log.info("Retrieving all comments for issue: {}", issueKey);
        return collectAllCommentsRecursively(issueKey, 0, new ArrayList<>());
    }

    @Override
    public CompletableFuture<List<JiraAttachmentInfo>> getAttachments(String issueKey) {
        log.info("Retrieving attachments for issue: {}", issueKey);
        var jql = "key = " + issueKey;
        var body = buildSearchBody(jql, 0, 1, List.of("attachment"));
        var uri = URI.create(config.getRestApiUrl() + "/search");
        var request = buildPostRequest(uri, body);
        return retryExecutor.executeRequest(request)
            .thenApply(this::parseAttachmentsFromSearchResponse);
    }

    @Override
    public CompletableFuture<byte[]> getAttachmentContent(String attachmentId) {
        log.info("Downloading attachment content: {}", attachmentId);
        var uri = URI.create(config.getRestApiUrl() + "/attachment/content/" + attachmentId);
        var request = buildGetRequestForBytes(uri);
        return retryExecutor.executeRequest(request)
            .thenApply(response -> {
                if (response.statusCode() != 200) {
                    log.error("Error downloading attachment {}: HTTP {}", attachmentId, response.statusCode());
                    return new byte[0];
                }
                return response.body().getBytes(StandardCharsets.ISO_8859_1);
            });
    }

    // --- Project pagination ---

    private CompletableFuture<List<JiraProject>> collectAllProjectsRecursively(
        int startAt, List<JiraProject> accumulated
    ) {
        var uri = URI.create(config.getRestApiUrl()
            + "/project/search?startAt=" + startAt
            + "&maxResults=50&expand=insight");
        var request = buildGetRequest(uri);

        return retryExecutor.executeRequest(request)
            .thenCompose(response -> processProjectsBatch(response, startAt, accumulated));
    }

    private CompletableFuture<List<JiraProject>> processProjectsBatch(
        HttpResponse<String> response, int startAt, List<JiraProject> accumulated
    ) {
        if (response.statusCode() != 200) {
            log.error("Error retrieving projects: HTTP {}", response.statusCode());
            return CompletableFuture.completedFuture(accumulated);
        }

        try {
            var root = objectMapper.readTree(response.body());
            var values = root.get("values");
            if (values == null || !values.isArray() || values.isEmpty()) {
                return CompletableFuture.completedFuture(accumulated);
            }

            var batch = parseProjects(values);
            var merged = new ArrayList<>(accumulated);
            merged.addAll(batch);

            var total = root.has("total") ? root.get("total").asInt() : 0;
            if (merged.size() < total) {
                return collectAllProjectsRecursively(startAt + values.size(), merged);
            }
            return CompletableFuture.completedFuture(merged);
        } catch (Exception e) {
            log.error("Error parsing projects response", e);
            return CompletableFuture.completedFuture(accumulated);
        }
    }

    private List<JiraProject> parseProjects(JsonNode valuesNode) {
        var projects = new ArrayList<JiraProject>();
        for (var node : valuesNode) {
            projects.add(parseSingleProject(node));
        }
        return projects;
    }

    private JiraProject parseSingleProject(JsonNode node) {
        var id = textOrEmpty(node, "id");
        var key = textOrEmpty(node, "key");
        var name = textOrEmpty(node, "name");
        var description = textOrEmpty(node, "description");

        var leadName = "";
        if (node.has("lead") && node.get("lead").has("displayName")) {
            leadName = node.get("lead").get("displayName").asText("");
        }

        var url = textOrEmpty(node, "self");
        var issueCount = 0;
        if (node.has("insight") && node.get("insight").has("totalIssueCount")) {
            issueCount = node.get("insight").get("totalIssueCount").asInt(0);
        }

        return new JiraProject(id, key, name, description, leadName, url, issueCount, null);
    }

    // --- Issue search with JQL (POST /search) ---

    private CompletableFuture<List<JiraIssue>> collectAllIssuesRecursively(
        String jql, int startAt, List<JiraIssue> accumulated
    ) {
        if (accumulated.size() >= config.maxIssues()) {
            log.warn("Max issues limit ({}) reached", config.maxIssues());
            return CompletableFuture.completedFuture(accumulated);
        }

        var body = buildSearchBody(jql, startAt, config.issuesLimit(), ISSUE_FIELDS);
        var uri = URI.create(config.getRestApiUrl() + "/search");
        var request = buildPostRequest(uri, body);

        return retryExecutor.executeRequest(request)
            .thenCompose(response -> processIssuesBatch(response, jql, startAt, accumulated));
    }

    private CompletableFuture<List<JiraIssue>> processIssuesBatch(
        HttpResponse<String> response, String jql, int startAt, List<JiraIssue> accumulated
    ) {
        if (response.statusCode() != 200) {
            log.error("Error searching issues: HTTP {}", response.statusCode());
            return CompletableFuture.completedFuture(accumulated);
        }

        try {
            var root = objectMapper.readTree(response.body());
            var issues = root.get("issues");
            if (issues == null || !issues.isArray() || issues.isEmpty()) {
                return CompletableFuture.completedFuture(accumulated);
            }

            var batch = parseIssues(issues);
            var merged = new ArrayList<>(accumulated);
            merged.addAll(batch);

            var total = root.has("total") ? root.get("total").asInt() : 0;
            if (merged.size() < total && merged.size() < config.maxIssues()) {
                return collectAllIssuesRecursively(jql, startAt + issues.size(), merged);
            }
            return CompletableFuture.completedFuture(merged);
        } catch (Exception e) {
            log.error("Error parsing issues response", e);
            return CompletableFuture.completedFuture(accumulated);
        }
    }

    private List<JiraIssue> parseIssues(JsonNode issuesNode) {
        var issues = new ArrayList<JiraIssue>();
        for (var node : issuesNode) {
            issues.add(parseSingleIssue(node));
        }
        return issues;
    }

    private JiraIssue parseSingleIssue(JsonNode node) {
        var id = textOrEmpty(node, "id");
        var key = textOrEmpty(node, "key");
        var fields = node.get("fields");
        if (fields == null) {
            return JiraIssue.builder().id(id).key(key).build();
        }

        var summary = textOrEmpty(fields, "summary");
        var projectKey = extractProjectKey(fields);
        var descriptionText = parseAdfField(fields.get("description"));
        var comments = parseEmbeddedComments(fields);
        var metadata = parseIssueMetadata(fields);

        return JiraIssue.builder()
            .id(id)
            .key(key)
            .projectKey(projectKey)
            .summary(summary)
            .descriptionText(descriptionText)
            .comments(comments)
            .metadata(metadata)
            .build();
    }

    private String extractProjectKey(JsonNode fields) {
        if (fields.has("project") && fields.get("project").has("key")) {
            return fields.get("project").get("key").asText("");
        }
        return "";
    }

    private String parseAdfField(JsonNode descriptionNode) {
        if (descriptionNode == null || descriptionNode.isNull()) {
            return "";
        }
        return adfParser.toPlainText(descriptionNode.toString());
    }

    private List<JiraComment> parseEmbeddedComments(JsonNode fields) {
        var comments = new ArrayList<JiraComment>();
        if (!fields.has("comment")) {
            return comments;
        }
        var commentField = fields.get("comment");
        var commentsArray = commentField.has("comments") ? commentField.get("comments") : null;
        if (commentsArray == null || !commentsArray.isArray()) {
            return comments;
        }
        for (var cNode : commentsArray) {
            comments.add(parseSingleComment(cNode));
        }
        return comments;
    }

    private JiraIssue.IssueMetadata parseIssueMetadata(JsonNode fields) {
        var reporter = extractDisplayName(fields, "reporter");
        var assignee = extractDisplayName(fields, "assignee");
        var status = extractNameField(fields, "status");
        var issueType = extractNameField(fields, "issuetype");
        var created = parseInstantField(fields, "created");
        var updated = parseInstantField(fields, "updated");
        return new JiraIssue.IssueMetadata(reporter, assignee, status, issueType, created, updated);
    }

    private String extractDisplayName(JsonNode fields, String fieldName) {
        if (fields.has(fieldName) && !fields.get(fieldName).isNull()
                && fields.get(fieldName).has("displayName")) {
            return fields.get(fieldName).get("displayName").asText("");
        }
        return null;
    }

    private String extractNameField(JsonNode fields, String fieldName) {
        if (fields.has(fieldName) && !fields.get(fieldName).isNull()
                && fields.get(fieldName).has("name")) {
            return fields.get(fieldName).get("name").asText("");
        }
        return null;
    }

    private Instant parseInstantField(JsonNode fields, String fieldName) {
        if (fields.has(fieldName) && !fields.get(fieldName).isNull()) {
            try {
                return Instant.parse(fields.get(fieldName).asText());
            } catch (Exception _) {
                return null;
            }
        }
        return null;
    }

    // --- Comments pagination ---

    private CompletableFuture<List<JiraComment>> collectAllCommentsRecursively(
        String issueKey, int startAt, List<JiraComment> accumulated
    ) {
        var uri = URI.create(config.getRestApiUrl()
            + "/issue/" + issueKey + "/comment?startAt=" + startAt + "&maxResults=50");
        var request = buildGetRequest(uri);

        return retryExecutor.executeRequest(request)
            .thenCompose(response -> processCommentsBatch(response, issueKey, startAt, accumulated));
    }

    private CompletableFuture<List<JiraComment>> processCommentsBatch(
        HttpResponse<String> response, String issueKey, int startAt, List<JiraComment> accumulated
    ) {
        if (response.statusCode() != 200) {
            log.error("Error retrieving comments for {}: HTTP {}", issueKey, response.statusCode());
            return CompletableFuture.completedFuture(accumulated);
        }

        try {
            var root = objectMapper.readTree(response.body());
            var comments = root.get("comments");
            if (comments == null || !comments.isArray() || comments.isEmpty()) {
                return CompletableFuture.completedFuture(accumulated);
            }

            var batch = new ArrayList<JiraComment>();
            for (var cNode : comments) {
                batch.add(parseSingleComment(cNode));
            }

            var merged = new ArrayList<>(accumulated);
            merged.addAll(batch);

            var total = root.has("total") ? root.get("total").asInt() : 0;
            if (merged.size() < total) {
                return collectAllCommentsRecursively(issueKey, startAt + comments.size(), merged);
            }
            return CompletableFuture.completedFuture(merged);
        } catch (Exception e) {
            log.error("Error parsing comments for {}", issueKey, e);
            return CompletableFuture.completedFuture(accumulated);
        }
    }

    private JiraComment parseSingleComment(JsonNode node) {
        var id = textOrEmpty(node, "id");
        var authorName = "";
        if (node.has("author") && node.get("author").has("displayName")) {
            authorName = node.get("author").get("displayName").asText("");
        }
        var bodyText = parseAdfField(node.get("body"));
        var created = parseInstantFromNode(node, "created");
        var updated = parseInstantFromNode(node, "updated");
        return new JiraComment(id, authorName, bodyText, created, updated);
    }

    private Instant parseInstantFromNode(JsonNode node, String fieldName) {
        if (node.has(fieldName) && !node.get(fieldName).isNull()) {
            try {
                return Instant.parse(node.get(fieldName).asText());
            } catch (Exception _) {
                return null;
            }
        }
        return null;
    }

    // --- Attachment parsing from search response ---

    private List<JiraAttachmentInfo> parseAttachmentsFromSearchResponse(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return List.of();
        }
        try {
            var root = objectMapper.readTree(response.body());
            var issues = root.get("issues");
            if (issues == null || !issues.isArray() || issues.isEmpty()) {
                return List.of();
            }
            var fields = issues.get(0).get("fields");
            if (fields == null || !fields.has("attachment")) {
                return List.of();
            }
            var attachments = fields.get("attachment");
            if (!attachments.isArray()) {
                return List.of();
            }
            var result = new ArrayList<JiraAttachmentInfo>();
            for (var aNode : attachments) {
                result.add(parseSingleAttachment(aNode));
            }
            return result;
        } catch (Exception e) {
            log.error("Error parsing attachments response", e);
            return List.of();
        }
    }

    private JiraAttachmentInfo parseSingleAttachment(JsonNode node) {
        var id = textOrEmpty(node, "id");
        var filename = textOrEmpty(node, "filename");
        var mimeType = textOrEmpty(node, "mimeType");
        var size = node.has("size") ? node.get("size").asLong(0) : 0;
        var contentUrl = textOrEmpty(node, "content");
        var author = "";
        if (node.has("author") && node.get("author").has("displayName")) {
            author = node.get("author").get("displayName").asText("");
        }
        return new JiraAttachmentInfo(id, filename, mimeType, size, contentUrl, author);
    }

    // --- HTTP request builders ---

    private HttpClient buildHttpClient() {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        return HttpClient.newBuilder()
            .executor(executor)
            .connectTimeout(Duration.ofMillis(config.connectTimeout()))
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    private String encodeBasicAuth(String email, String apiToken) {
        var credentials = email.trim() + ":" + apiToken.trim();
        return "Basic " + Base64.getEncoder()
            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String getAuthHeader() {
        return encodeBasicAuth(config.email(), config.apiToken());
    }

    private HttpRequest buildGetRequest(URI uri) {
        return HttpRequest.newBuilder()
            .uri(uri)
            .header(AUTHORIZATION_HEADER, getAuthHeader())
            .header(ACCEPT_HEADER, CONTENT_TYPE_JSON)
            .timeout(Duration.ofMillis(config.readTimeout()))
            .GET()
            .build();
    }

    private HttpRequest buildGetRequestForBytes(URI uri) {
        return HttpRequest.newBuilder()
            .uri(uri)
            .header(AUTHORIZATION_HEADER, getAuthHeader())
            .timeout(Duration.ofMillis(config.readTimeout()))
            .GET()
            .build();
    }

    private HttpRequest buildPostRequest(URI uri, String body) {
        return HttpRequest.newBuilder()
            .uri(uri)
            .header(AUTHORIZATION_HEADER, getAuthHeader())
            .header(ACCEPT_HEADER, CONTENT_TYPE_JSON)
            .header("Content-Type", CONTENT_TYPE_JSON)
            .timeout(Duration.ofMillis(config.readTimeout()))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    private String buildSearchBody(String jql, int startAt, int maxResults, List<String> fields) {
        try {
            var root = objectMapper.createObjectNode();
            root.put("jql", jql);
            root.put("startAt", startAt);
            root.put("maxResults", maxResults);
            var fieldsArray = root.putArray("fields");
            fields.forEach(fieldsArray::add);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("Error building search body", e);
            return "{}";
        }
    }

    private String textOrEmpty(JsonNode node, String field) {
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText("") : "";
    }
}
