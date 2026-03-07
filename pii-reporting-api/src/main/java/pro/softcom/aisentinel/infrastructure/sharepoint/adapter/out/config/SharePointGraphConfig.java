package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.config;

import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.authentication.AccessTokenProvider;
import com.microsoft.kiota.authentication.AllowedHostsValidator;
import com.microsoft.kiota.authentication.BaseBearerTokenAuthenticationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Map;

/**
 * Spring configuration for Microsoft Graph SDK client.
 * Supports two authentication modes:
 * <ul>
 *   <li><b>Azure AD (Client Credentials)</b>: when tenant-id, client-id and client-secret are set</li>
 *   <li><b>Static access token</b>: when only access-token is set (useful for development/testing)</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "sharepoint", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(SharePointProperties.class)
@Slf4j
public class SharePointGraphConfig {

    /**
     * Creates a GraphServiceClient using Azure AD Client Credentials flow.
     * Activated when tenant-id, client-id and client-secret are all configured.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sharepoint", name = {"tenant-id", "client-id", "client-secret"})
    public GraphServiceClient graphServiceClientWithClientCredentials(SharePointProperties properties) {
        log.info("Initializing Microsoft Graph client with Azure AD Client Credentials");
        var credential = new ClientSecretCredentialBuilder()
            .tenantId(properties.tenantId())
            .clientId(properties.clientId())
            .clientSecret(properties.clientSecret())
            .build();
        return new GraphServiceClient(credential, "https://graph.microsoft.com/.default");
    }

    /**
     * Creates a GraphServiceClient using a static access token.
     * Fallback when Azure AD credentials are not configured but an access-token is provided.
     */
    @Bean
    @ConditionalOnProperty(prefix = "sharepoint", name = "access-token")
    public GraphServiceClient graphServiceClientWithStaticToken(SharePointProperties properties) {
        log.info("Initializing Microsoft Graph client with static access token");
        var tokenProvider = new StaticAccessTokenProvider(properties.accessToken());
        var authProvider = new BaseBearerTokenAuthenticationProvider(tokenProvider);
        return new GraphServiceClient(authProvider);
    }

    /**
     * Provides a static access token for Microsoft Graph API authentication.
     * Useful for development when the token is obtained externally (e.g., from Azure portal).
     */
    static class StaticAccessTokenProvider implements AccessTokenProvider {

        private final String accessToken;
        private final AllowedHostsValidator validator;

        StaticAccessTokenProvider(String accessToken) {
            this.accessToken = accessToken;
            this.validator = new AllowedHostsValidator("graph.microsoft.com");
        }

        @Override
        public String getAuthorizationToken(URI uri, Map<String, Object> additionalAuthenticationContext) {
            return accessToken;
        }

        @Override
        public AllowedHostsValidator getAllowedHostsValidator() {
            return validator;
        }
    }
}
