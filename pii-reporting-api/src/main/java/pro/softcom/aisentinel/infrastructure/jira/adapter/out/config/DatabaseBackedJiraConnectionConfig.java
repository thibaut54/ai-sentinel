package pro.softcom.aisentinel.infrastructure.jira.adapter.out.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.jira.port.out.JiraConnectionConfigRepository;
import pro.softcom.aisentinel.domain.jira.JiraConnectionSettings;
import pro.softcom.aisentinel.domain.jira.JiraDeploymentType;
import pro.softcom.aisentinel.domain.pii.security.EncryptionMetadata;
import pro.softcom.aisentinel.domain.pii.security.EncryptionService;

import java.util.concurrent.atomic.AtomicReference;

/**
 * DB-backed implementation of {@link JiraConnectionConfig}.
 * Reads connection settings from the database and decrypts the API token.
 */
@Component("jiraConfig")
@Slf4j
public class DatabaseBackedJiraConnectionConfig implements JiraConnectionConfig {

    private static final EncryptionMetadata TOKEN_METADATA = new EncryptionMetadata("JIRA_API_TOKEN", 0, 0);

    private final JiraConnectionConfigRepository repository;
    private final EncryptionService encryptionService;

    private final AtomicReference<JiraConnectionSettings> cachedSettings = new AtomicReference<>();

    public DatabaseBackedJiraConnectionConfig(JiraConnectionConfigRepository repository,
                                               EncryptionService encryptionService) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        log.info("Database-backed Jira connection config initialized");
    }

    public void invalidateCache() {
        cachedSettings.set(null);
    }

    @Override
    public String baseUrl() {
        return getSettings().baseUrl();
    }

    @Override
    public String email() {
        return getSettings().email();
    }

    @Override
    public String apiToken() {
        return repository.findDecryptedApiToken()
                .map(encrypted -> encryptionService.decrypt(encrypted, TOKEN_METADATA))
                .orElse("");
    }

    @Override
    public int connectTimeout() {
        return getSettings().connectTimeout();
    }

    @Override
    public int readTimeout() {
        return getSettings().readTimeout();
    }

    @Override
    public int maxRetries() {
        return getSettings().maxRetries();
    }

    @Override
    public int issuesLimit() {
        return getSettings().issuesLimit();
    }

    @Override
    public int maxIssues() {
        return getSettings().maxIssues();
    }

    @Override
    public JiraDeploymentType deploymentType() {
        return getSettings().deploymentType();
    }

    private JiraConnectionSettings getSettings() {
        JiraConnectionSettings settings = cachedSettings.get();
        if (settings == null) {
            settings = repository.findSettings()
                    .orElse(new JiraConnectionSettings(1, "", "", 30000, 60000, 3, 50, 5000, JiraDeploymentType.CLOUD, null, null));
            cachedSettings.set(settings);
        }
        return settings;
    }
}
