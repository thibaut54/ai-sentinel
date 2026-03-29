package pro.softcom.aisentinel.domain.jira;

public final class JiraAuthenticationException extends JiraException {
    public JiraAuthenticationException(String message, int statusCode) {
        super(message, statusCode, null);
    }
}
