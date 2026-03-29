package pro.softcom.aisentinel.domain.jira;

import lombok.Getter;

@Getter
public final class JiraNotFoundException extends JiraException {
    private final String resourceId;

    public JiraNotFoundException(String resourceId, String resourceType) {
        super(String.format("%s with ID '%s' not found", resourceType, resourceId), 404, null);
        this.resourceId = resourceId;
    }
}
