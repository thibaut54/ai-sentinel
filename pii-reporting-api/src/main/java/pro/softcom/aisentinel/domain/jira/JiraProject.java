package pro.softcom.aisentinel.domain.jira;

import java.time.Instant;

public record JiraProject(
    String id,
    String key,
    String name,
    String description,
    String leadDisplayName,
    String url,
    int issueCount,
    Instant lastIssueUpdateTime
) {
    public JiraProject {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Project key cannot be empty");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Project name cannot be empty");
        }
    }
}
