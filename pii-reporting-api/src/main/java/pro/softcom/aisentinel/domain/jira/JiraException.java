package pro.softcom.aisentinel.domain.jira;

import lombok.Getter;

@Getter
public sealed class JiraException extends RuntimeException permits JiraApiException,
    JiraAuthenticationException, JiraConnectionException, JiraNotFoundException {
    private final int statusCode;
    private final String jiraMessage;

    public JiraException(String message, int statusCode, String jiraMessage) {
        super(message);
        this.statusCode = statusCode;
        this.jiraMessage = jiraMessage;
    }

    public JiraException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = -1;
        this.jiraMessage = null;
    }
}
