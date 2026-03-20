package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.jira.port.out.JiraUrlProvider;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

/**
 * Infrastructure adapter that exposes Jira baseUrl and builds public URLs
 * through the application-level JiraUrlProvider.
 */
@Component
public class JiraUrlProviderAdapter implements JiraUrlProvider {

    private final JiraConnectionConfig jiraConnectionConfig;

    public JiraUrlProviderAdapter(@Qualifier("jiraConfig") JiraConnectionConfig jiraConnectionConfig) {
        this.jiraConnectionConfig = jiraConnectionConfig;
    }

    @Override
    public String baseUrl() {
        return jiraConnectionConfig.baseUrl();
    }

    @Override
    public String issueUrl(String issueKey) {
        if (issueKey == null || issueKey.isBlank()) {
            return null;
        }
        String base = baseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        base = base.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/browse/" + issueKey;
    }
}
