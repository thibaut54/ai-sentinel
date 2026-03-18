package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

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
        return BEARER_AUTH_PREFIX + config.apiToken().trim();
    }
}
