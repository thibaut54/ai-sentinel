package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import pro.softcom.aisentinel.application.jira.service.AdfContentParser;
import pro.softcom.aisentinel.domain.jira.JiraProject;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.http.HttpRetryExecutor;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * HTTP Adapter for Jira Cloud REST API v3.
 * Implements the JiraClient port using java.net.http.HttpClient with HTTP/2 and virtual threads.
 * <p>
 * This adapter targets Jira Cloud instances (*.atlassian.net) only.
 * For Jira Server/Data Center, use {@link JiraDataCenterHttpClientAdapter}.
 */
@Slf4j
public class JiraCloudHttpClientAdapter extends AbstractJiraHttpClientAdapter {

    private static final String BASIC_AUTH_PREFIX = "Basic ";

    private final AdfContentParser adfParser;

    public JiraCloudHttpClientAdapter(
        @Qualifier("jiraConfig") JiraConnectionConfig config,
        ObjectMapper objectMapper,
        AdfContentParser adfParser
    ) {
        this(config, objectMapper, adfParser, new HttpRetryExecutor(buildHttpClient(config), config.maxRetries()));
    }

    JiraCloudHttpClientAdapter(JiraConnectionConfig config, ObjectMapper objectMapper,
                               AdfContentParser adfParser, HttpRetryExecutor retryExecutor) {
        super(config, objectMapper, retryExecutor);
        this.adfParser = adfParser;
    }

    // --- Template method implementations ---

    @Override
    protected String getAuthHeader() {
        var credentials = config.email().trim() + ":" + config.apiToken().trim();
        return BASIC_AUTH_PREFIX + Base64.getEncoder()
            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected String parseTextContent(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return adfParser.toPlainText(node.toString());
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

    // --- Cloud-specific: paginated project retrieval ---

    @Override
    public CompletableFuture<List<JiraProject>> getAllProjects() {
        log.info("Retrieving all Jira projects");
        return collectAllProjectsRecursively(0, new ArrayList<>());
    }

    private CompletableFuture<List<JiraProject>> collectAllProjectsRecursively(
        int startAt, List<JiraProject> accumulated
    ) {
        var uri = URI.create(config.getRestApiUrl()
            + config.projectSearchPath() + "?startAt=" + startAt
            + "&maxResults=" + DEFAULT_PAGE_SIZE + "&expand=insight");
        var request = buildGetRequest(uri);

        return retryExecutor.executeRequest(request)
            .thenCompose(response -> processProjectsBatch(response, startAt, accumulated));
    }

    private CompletableFuture<List<JiraProject>> processProjectsBatch(
        HttpResponse<String> response, int startAt, List<JiraProject> accumulated
    ) {
        if (response.statusCode() != HTTP_OK) {
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

            var total = root.has(FIELD_NAME_TOTAL) ? root.get(FIELD_NAME_TOTAL).asInt() : 0;
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
            projects.add(parseSingleProject(node, true));
        }
        return projects;
    }

    // --- Cloud-specific: direct attachment download ---

    @Override
    public CompletableFuture<byte[]> getAttachmentContent(String attachmentId) {
        log.info("Downloading attachment content: {}", attachmentId);
        var uri = URI.create(config.getRestApiUrl() + config.attachmentContentPath() + attachmentId);
        var request = buildGetRequestForBytes(uri);
        return retryExecutor.executeRequest(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenApply(response -> {
                if (response.statusCode() != HTTP_OK) {
                    log.error("Error downloading attachment {}: HTTP {}", attachmentId, response.statusCode());
                    return new byte[0];
                }
                return response.body();
            });
    }

    // --- HTTP client factory ---

    private static HttpClient buildHttpClient(JiraConnectionConfig config) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        return HttpClient.newBuilder()
            .executor(executor)
            .connectTimeout(Duration.ofMillis(config.connectTimeout()))
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }
}
