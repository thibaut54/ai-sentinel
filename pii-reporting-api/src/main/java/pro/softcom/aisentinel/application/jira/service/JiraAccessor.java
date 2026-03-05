package pro.softcom.aisentinel.application.jira.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.jira.port.out.JiraClient;
import pro.softcom.aisentinel.domain.jira.JiraAttachmentInfo;
import pro.softcom.aisentinel.domain.jira.JiraIssue;
import pro.softcom.aisentinel.domain.jira.JiraProject;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Encapsulates Jira data access operations with cache-first strategy.
 * Business purpose: Provides a unified interface for retrieving Jira projects and issues,
 * caching projects to improve stream startup performance.
 */
@RequiredArgsConstructor
@Slf4j
public class JiraAccessor {

    private final JiraClient jiraClient;
    private final List<JiraProject> cachedProjects = new CopyOnWriteArrayList<>();

    /**
     * Retrieves all projects using cache-first strategy.
     */
    public CompletableFuture<List<JiraProject>> getAllProjects() {
        log.debug("Fetching Jira projects with cache-first strategy");

        if (!cachedProjects.isEmpty()) {
            log.debug("Returning {} cached projects", cachedProjects.size());
            return CompletableFuture.completedFuture(List.copyOf(cachedProjects));
        }

        log.debug("Cache miss - fetching projects from Jira API");
        return jiraClient.getAllProjects()
                .thenApply(projects -> {
                    if (projects != null && !projects.isEmpty()) {
                        cachedProjects.clear();
                        cachedProjects.addAll(projects);
                        log.info("Cached {} projects from Jira API", projects.size());
                    }
                    return projects;
                });
    }

    public CompletableFuture<List<JiraIssue>> getIssuesInProject(String projectKey) {
        return jiraClient.getIssuesInProject(projectKey);
    }

    public CompletableFuture<List<JiraAttachmentInfo>> getAttachments(String issueKey) {
        return jiraClient.getAttachments(issueKey);
    }

    public CompletableFuture<byte[]> getAttachmentContent(String attachmentId) {
        return jiraClient.getAttachmentContent(attachmentId);
    }

    /**
     * Clears the project cache, forcing a fresh fetch on next access.
     */
    public void clearCache() {
        cachedProjects.clear();
        log.debug("Jira project cache cleared");
    }
}
