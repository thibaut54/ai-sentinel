package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Confluence Cloud HTTP adapter.
 * Uses Basic Auth (email:apiToken) for authentication against Confluence Cloud instances.
 */
@Slf4j
public class ConfluenceCloudHttpClientAdapter extends AbstractConfluenceHttpClientAdapter {

    private static final String BASIC_AUTH_PREFIX = "Basic ";

    public ConfluenceCloudHttpClientAdapter(ConfluenceConnectionConfig config, ObjectMapper objectMapper) {
        super(config, objectMapper);
    }

    @Override
    protected String getAuthHeader() {
        var credentials = config.username().trim() + ":" + config.apiToken().trim();
        return BASIC_AUTH_PREFIX + Base64.getEncoder()
            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
