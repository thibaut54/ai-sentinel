package pro.softcom.aisentinel.domain.jira;

public final class JiraApiException extends JiraException {
    public JiraApiException(String message, int statusCode, String jiraMessage) {
        super(message, statusCode, jiraMessage);
    }
}
