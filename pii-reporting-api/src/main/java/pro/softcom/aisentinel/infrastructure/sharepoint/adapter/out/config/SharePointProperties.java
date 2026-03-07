package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for SharePoint integration via Microsoft Graph API.
 *
 * @param enabled       whether SharePoint integration is active
 * @param tenantId      Azure AD tenant identifier
 * @param clientId      Azure AD application (client) identifier
 * @param clientSecret  Azure AD client secret
 * @param accessToken   static access token (alternative to Azure AD credentials, useful for dev/testing)
 */
@ConfigurationProperties(prefix = "sharepoint")
public record SharePointProperties(
    boolean enabled,
    String tenantId,
    String clientId,
    String clientSecret,
    String accessToken
) {
}
