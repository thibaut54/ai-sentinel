package pro.softcom.aisentinel.application.jira.port.in;

import pro.softcom.aisentinel.domain.jira.JiraProject;

import java.util.List;

/**
 * Port IN for retrieving Jira project information.
 */
public interface JiraProjectPort {
    List<JiraProject> getAllProjects();
    JiraProject getProject(String projectKey);
}
