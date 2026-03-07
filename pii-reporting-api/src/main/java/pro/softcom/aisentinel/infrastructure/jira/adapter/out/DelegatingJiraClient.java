package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.jira.port.out.JiraClient;
import pro.softcom.aisentinel.domain.jira.*;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Delegates to either the Cloud or Data Center Jira adapter
 * based on the configured {@link JiraDeploymentType}.
 */
@Slf4j
public class DelegatingJiraClient implements JiraClient {

    private final JiraConnectionConfig config;
    private final JiraClient cloudAdapter;
    private final JiraClient dataCenterAdapter;

    public DelegatingJiraClient(JiraConnectionConfig config,
                                JiraClient cloudAdapter,
                                JiraClient dataCenterAdapter) {
        this.config = config;
        this.cloudAdapter = cloudAdapter;
        this.dataCenterAdapter = dataCenterAdapter;
    }

    private JiraClient delegate() {
        var type = config.deploymentType();
        if (type == JiraDeploymentType.DATA_CENTER) {
            log.debug("Using Jira Data Center adapter");
            return dataCenterAdapter;
        }
        log.debug("Using Jira Cloud adapter");
        return cloudAdapter;
    }

    @Override
    public CompletableFuture<Boolean> testConnection() {
        return delegate().testConnection();
    }

    @Override
    public CompletableFuture<List<JiraProject>> getAllProjects() {
        return delegate().getAllProjects();
    }

    @Override
    public CompletableFuture<List<JiraIssue>> getIssuesInProject(String projectKey) {
        return delegate().getIssuesInProject(projectKey);
    }

    @Override
    public CompletableFuture<List<JiraIssue>> getIssuesUpdatedSince(String projectKey, Instant since) {
        return delegate().getIssuesUpdatedSince(projectKey, since);
    }

    @Override
    public CompletableFuture<List<JiraComment>> getAllComments(String issueKey) {
        return delegate().getAllComments(issueKey);
    }

    @Override
    public CompletableFuture<List<JiraAttachmentInfo>> getAttachments(String issueKey) {
        return delegate().getAttachments(issueKey);
    }

    @Override
    public CompletableFuture<byte[]> getAttachmentContent(String attachmentId) {
        return delegate().getAttachmentContent(attachmentId);
    }
}
