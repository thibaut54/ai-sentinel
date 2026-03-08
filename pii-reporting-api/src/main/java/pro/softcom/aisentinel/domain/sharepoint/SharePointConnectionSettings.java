package pro.softcom.aisentinel.domain.sharepoint;

import java.time.Instant;

/**
 * Domain model for SharePoint connection configuration.
 * Represents the connection settings for the SharePoint M365 integration.
 *
 * <p>Note: The clientSecret is NOT part of the domain model because it is an infrastructure
 * concern (encrypted storage). The domain only deals with non-sensitive connection parameters.
 *
 * @param id        configuration identifier
 * @param tenantId  Azure AD tenant identifier
 * @param clientId  Azure AD application (client) identifier
 * @param enabled   whether this SharePoint connection is active
 * @param updatedAt timestamp of last configuration update
 * @param updatedBy user who last updated the configuration
 */
public record SharePointConnectionSettings(
    Long id,
    String tenantId,
    String clientId,
    boolean enabled,
    Instant updatedAt,
    String updatedBy
) {
    public SharePointConnectionSettings {
        if (tenantId == null) tenantId = "";
        if (clientId == null) clientId = "";
    }
}
