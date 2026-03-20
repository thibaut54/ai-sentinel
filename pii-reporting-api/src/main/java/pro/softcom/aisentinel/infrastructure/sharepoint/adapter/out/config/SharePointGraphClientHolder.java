package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.config;

import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointConnectionConfigRepository;
import pro.softcom.aisentinel.domain.pii.security.EncryptionMetadata;
import pro.softcom.aisentinel.domain.pii.security.EncryptionService;
import pro.softcom.aisentinel.domain.sharepoint.SharePointConnectionSettings;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lazily creates and caches a GraphServiceClient from DB-backed configuration.
 * Invalidates on config update, so the next call creates a fresh client.
 */
@Component
@Slf4j
public class SharePointGraphClientHolder {

    private static final EncryptionMetadata SECRET_METADATA = new EncryptionMetadata("SHAREPOINT_CLIENT_SECRET", 0, 0);

    private final SharePointConnectionConfigRepository configRepository;
    private final EncryptionService encryptionService;
    private final AtomicReference<GraphServiceClient> cachedClient = new AtomicReference<>();

    public SharePointGraphClientHolder(SharePointConnectionConfigRepository configRepository,
                                        EncryptionService encryptionService) {
        this.configRepository = configRepository;
        this.encryptionService = encryptionService;
        log.info("SharePoint GraphClient holder initialized (DB-backed)");
    }

    public GraphServiceClient getClient() {
        GraphServiceClient client = cachedClient.get();
        if (client != null) {
            return client;
        }

        synchronized (this) {
            client = cachedClient.get();
            if (client != null) {
                return client;
            }

            client = createClient();
            cachedClient.set(client);
            return client;
        }
    }

    public void invalidate() {
        cachedClient.set(null);
        log.info("SharePoint GraphClient invalidated (will be recreated on next use)");
    }

    private GraphServiceClient createClient() {
        SharePointConnectionSettings settings = configRepository.findSettings()
                .orElseThrow(() -> new IllegalStateException("SharePoint configuration not found in database"));

        String encryptedSecret = configRepository.findEncryptedClientSecret()
                .orElseThrow(() -> new IllegalStateException("SharePoint client secret not found in database"));

        String clientSecret = encryptionService.decrypt(encryptedSecret, SECRET_METADATA);

        log.debug("Creating GraphServiceClient from DB config: tenantId={}, clientId={}",
                settings.tenantId(), settings.clientId());

        var credential = new ClientSecretCredentialBuilder()
                .tenantId(settings.tenantId())
                .clientId(settings.clientId())
                .clientSecret(clientSecret)
                .build();

        return new GraphServiceClient(credential, "https://graph.microsoft.com/.default");
    }
}
