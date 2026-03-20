package pro.softcom.aisentinel.application.jira.port.out;

import pro.softcom.aisentinel.domain.jira.JiraAttachmentInfo;
import pro.softcom.aisentinel.domain.jira.JiraComment;
import pro.softcom.aisentinel.domain.jira.JiraIssue;
import pro.softcom.aisentinel.domain.jira.JiraProject;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Port OUT for Jira API interactions.
 * Describes the capabilities needed by the application layer to interact with Jira.
 */
public interface JiraClient {

    CompletableFuture<Boolean> testConnection();

    CompletableFuture<List<JiraProject>> getAllProjects();

    CompletableFuture<List<JiraIssue>> getIssuesInProject(String projectKey);

    CompletableFuture<List<JiraIssue>> getIssuesUpdatedSince(String projectKey, Instant since);

    /**
     * Fetch ALL comments for an issue (paginated internally).
     * The issue search endpoint only returns max 20 comments.
     */
    CompletableFuture<List<JiraComment>> getAllComments(String issueKey);

    CompletableFuture<List<JiraAttachmentInfo>> getAttachments(String issueKey);

    CompletableFuture<byte[]> getAttachmentContent(String attachmentId);
}
