package pro.softcom.aisentinel.application.sharepoint.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.softcom.aisentinel.application.sharepoint.port.in.ManageSharePointConnectionPort;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointConnectionConfigRepository;
import pro.softcom.aisentinel.domain.pii.security.EncryptionMetadata;
import pro.softcom.aisentinel.domain.pii.security.EncryptionService;
import pro.softcom.aisentinel.domain.sharepoint.SharePointConnectionSettings;

import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;

import java.time.Instant;

/**
 * Use case for managing SharePoint connection configuration.
 * Handles retrieval, update, and connection testing.
 */
public class ManageSharePointConnectionUseCase implements ManageSharePointConnectionPort {

    private static final Logger log = LoggerFactory.getLogger(ManageSharePointConnectionUseCase.class);
    private static final Long CONFIG_ID = 1L;
    private static final EncryptionMetadata SECRET_METADATA = new EncryptionMetadata("SHAREPOINT_CLIENT_SECRET", 0, 0);

    private final SharePointConnectionConfigRepository repository;
    private final EncryptionService encryptionService;
    private final Runnable onConfigUpdated;

    public ManageSharePointConnectionUseCase(SharePointConnectionConfigRepository repository,
                                              EncryptionService encryptionService,
                                              Runnable onConfigUpdated) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.onConfigUpdated = onConfigUpdated;
    }

    @Override
    public SharePointConnectionSettings getConnectionSettings() {
        log.debug("Retrieving SharePoint connection settings");
        return repository.findSettings()
                .orElse(new SharePointConnectionSettings(CONFIG_ID, "", "", false, null, null));
    }

    @Override
    public boolean isConfigured() {
        return repository.findDecryptedClientSecret()
                .filter(secret -> !secret.isBlank())
                .isPresent();
    }

    @Override
    public SharePointConnectionSettings updateConnectionSettings(UpdateSharePointConnectionCommand command) {
        log.info("Updating SharePoint connection settings: tenantId={}, clientId={}, enabled={}",
                command.tenantId(), command.clientId(), command.enabled());

        var newSettings = new SharePointConnectionSettings(
                CONFIG_ID,
                command.tenantId(),
                command.clientId(),
                command.enabled(),
                Instant.now(),
                command.updatedBy()
        );

        repository.save(newSettings);

        if (command.clientSecret() != null && !command.clientSecret().isBlank()) {
            log.debug("Encrypting and saving new client secret");
            String encrypted = encryptionService.encrypt(command.clientSecret(), SECRET_METADATA);
            repository.saveEncryptedClientSecret(encrypted);
        }

        onConfigUpdated.run();

        log.info("SharePoint connection settings updated successfully by user: {}", command.updatedBy());
        return newSettings;
    }

    @Override
    public boolean testConnection(TestSharePointConnectionCommand command) {
        log.info("Testing SharePoint connection: tenantId={}, clientId={}", command.tenantId(), command.clientId());

        try {
            String clientSecret = command.clientSecret();
            if (clientSecret == null || clientSecret.isBlank()) {
                clientSecret = repository.findDecryptedClientSecret()
                        .map(encrypted -> encryptionService.decrypt(encrypted, SECRET_METADATA))
                        .orElse("");
            }

            var credential = new ClientSecretCredentialBuilder()
                    .tenantId(command.tenantId())
                    .clientId(command.clientId())
                    .clientSecret(clientSecret)
                    .build();

            var graphClient = new GraphServiceClient(credential, "https://graph.microsoft.com/.default");
            var root = graphClient.sites().bySiteId("root").get();

            boolean success = root != null;
            log.info("SharePoint connection test result: {}", success ? "SUCCESS" : "FAILED");
            return success;

        } catch (Exception e) {
            log.warn("SharePoint connection test failed: {}", e.getMessage());
            return false;
        }
    }
}
