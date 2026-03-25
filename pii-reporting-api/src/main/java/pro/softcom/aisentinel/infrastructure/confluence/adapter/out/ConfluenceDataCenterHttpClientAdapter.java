package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.domain.confluence.ConfluenceSpace;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Confluence Data Center HTTP adapter.
 * Uses Bearer token (Personal Access Token) for authentication
 * against Confluence Data Center instances.
 */
@Slf4j
public class ConfluenceDataCenterHttpClientAdapter extends AbstractConfluenceHttpClientAdapter {

    private static final String BEARER_AUTH_PREFIX = "Bearer ";

    public ConfluenceDataCenterHttpClientAdapter(ConfluenceConnectionConfig config, ObjectMapper objectMapper) {
        super(config, objectMapper);
    }

    @Override
    protected String getAuthHeader() {
        if (config.apiToken() == null || config.apiToken().isBlank()) {
            throw new IllegalStateException("Confluence Data Center requires a Personal Access Token");
        }
        return BEARER_AUTH_PREFIX + config.apiToken().trim();
    }

    /**
     * On Data Center, expand=permissions is not supported on the space endpoint
     * (CONFSERVER-78176). Falls back to getSpace() without permissions.
     * Data owners will not be available for Data Center spaces.
     */
    @Override
    public CompletableFuture<Optional<ConfluenceSpace>> getSpaceWithPermissions(String spaceKey) {
        log.info("Data Center: retrieving space without permissions expand (not supported): {}", spaceKey);
        return getSpace(spaceKey);
    }
}
