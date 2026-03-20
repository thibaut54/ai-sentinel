package pro.softcom.aisentinel.application.jira.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.jira.port.in.JiraProjectPort;
import pro.softcom.aisentinel.application.jira.service.JiraAccessor;
import pro.softcom.aisentinel.domain.jira.JiraProject;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Use case for retrieving Jira project information.
 * Delegates to JiraAccessor for cache-first data access.
 */
@RequiredArgsConstructor
@Slf4j
public class FetchJiraProjectUseCase implements JiraProjectPort {

    private final JiraAccessor jiraAccessor;

    @Override
    public List<JiraProject> getAllProjects() {
        log.info("Fetching all Jira projects");
        return jiraAccessor.getAllProjects().join();
    }

    @Override
    public JiraProject getProject(String projectKey) {
        log.info("Fetching Jira project: {}", projectKey);
        return jiraAccessor.getAllProjects().join().stream()
                .filter(p -> p.key().equals(projectKey))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("Project not found: " + projectKey));
    }
}
