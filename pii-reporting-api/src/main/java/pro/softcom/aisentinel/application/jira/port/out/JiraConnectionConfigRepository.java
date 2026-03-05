package pro.softcom.aisentinel.application.jira.port.out;

import pro.softcom.aisentinel.domain.jira.JiraConnectionSettings;

import java.util.Optional;

/**
 * Port OUT for Jira connection configuration persistence.
 * Defines repository operations for Jira connection settings.
 */
public interface JiraConnectionConfigRepository {
    Optional<JiraConnectionSettings> findSettings();
    JiraConnectionSettings save(JiraConnectionSettings settings);
    void saveEncryptedApiToken(String plainApiToken);
    Optional<String> findDecryptedApiToken();
}
