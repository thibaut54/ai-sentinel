package pro.softcom.aisentinel.application.jira.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.softcom.aisentinel.application.jira.port.in.ManageJiraConnectionPort;
import pro.softcom.aisentinel.application.jira.port.out.JiraConnectionConfigRepository;
import pro.softcom.aisentinel.domain.jira.JiraBaseUrl;
import pro.softcom.aisentinel.domain.jira.JiraConnectionSettings;
import pro.softcom.aisentinel.domain.pii.security.EncryptionMetadata;
import pro.softcom.aisentinel.domain.pii.security.EncryptionService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Use case for managing Jira connection configuration.
 * Handles retrieval, update, and connection testing.
 */
public class ManageJiraConnectionUseCase implements ManageJiraConnectionPort {

    private static final Logger log = LoggerFactory.getLogger(ManageJiraConnectionUseCase.class);
    private static final Integer CONFIG_ID = 1;
    private static final EncryptionMetadata TOKEN_METADATA = new EncryptionMetadata("JIRA_API_TOKEN", 0, 0);

    private final JiraConnectionConfigRepository repository;
    private final EncryptionService encryptionService;
    private final Runnable onConfigUpdated;

    public ManageJiraConnectionUseCase(JiraConnectionConfigRepository repository,
                                       EncryptionService encryptionService,
                                       Runnable onConfigUpdated) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.onConfigUpdated = onConfigUpdated;
    }

    @Override
    public JiraConnectionSettings getConnectionSettings() {
        log.debug("Retrieving Jira connection settings");
        return repository.findSettings()
                .orElse(new JiraConnectionSettings(CONFIG_ID, "", "", 30000, 60000, 3, 50, 5000, null, null));
    }

    @Override
    public boolean isConfigured() {
        return repository.findDecryptedApiToken()
                .filter(token -> !token.isBlank())
                .isPresent();
    }

    @Override
    public JiraConnectionSettings updateConnectionSettings(UpdateJiraConnectionCommand command) {
        var validatedUrl = new JiraBaseUrl(command.baseUrl());

        log.info("Updating Jira connection settings: baseUrl={}, email={}, connectTimeout={}, "
                        + "readTimeout={}, maxRetries={}, issuesLimit={}, maxIssues={}",
                validatedUrl.value(), command.email(), command.connectTimeout(),
                command.readTimeout(), command.maxRetries(), command.issuesLimit(), command.maxIssues());

        var newSettings = new JiraConnectionSettings(
                CONFIG_ID,
                validatedUrl.value(),
                command.email(),
                command.connectTimeout(),
                command.readTimeout(),
                command.maxRetries(),
                command.issuesLimit(),
                command.maxIssues(),
                Instant.now(),
                command.updatedBy()
        );

        repository.save(newSettings);

        if (command.apiToken() != null && !command.apiToken().isBlank()) {
            log.debug("Encrypting and saving new API token");
            String encryptedToken = encryptToken(command.apiToken());
            repository.saveEncryptedApiToken(encryptedToken);
        }

        onConfigUpdated.run();

        log.info("Jira connection settings updated successfully by user: {}", command.updatedBy());
        return newSettings;
    }

    @Override
    public boolean testConnection(TestJiraConnectionCommand command) {
        var validatedUrl = new JiraBaseUrl(command.baseUrl());
        log.info("Testing Jira connection to: {}", validatedUrl.value());

        try {
            String url = validatedUrl.value() + "/rest/api/3/myself";

            String credentials = Base64.getEncoder()
                    .encodeToString((command.email() + ":" + command.apiToken()).getBytes());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Basic " + credentials)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            log.info("Jira connection test result: {} (HTTP {})", success ? "SUCCESS" : "FAILED", response.statusCode());
            return success;

        } catch (InterruptedException e) {
            log.warn("Jira connection test interrupted: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Jira connection test failed: {}", e.getMessage());
            return false;
        }
    }

    private String encryptToken(String plainToken) {
        if (plainToken == null || plainToken.isBlank()) {
            return "";
        }
        return encryptionService.encrypt(plainToken, TOKEN_METADATA);
    }
}
