package pro.softcom.aisentinel.application.sharepoint.port.in;

import pro.softcom.aisentinel.domain.sharepoint.SharePointConnectionSettings;

import java.util.Objects;

/**
 * Port IN for managing SharePoint connection configuration.
 * Defines use cases for retrieving, updating, and testing SharePoint connection settings.
 */
public interface ManageSharePointConnectionPort {

    SharePointConnectionSettings getConnectionSettings();

    boolean isConfigured();

    SharePointConnectionSettings updateConnectionSettings(UpdateSharePointConnectionCommand command);

    boolean testConnection(TestSharePointConnectionCommand command);

    record UpdateSharePointConnectionCommand(
            String tenantId,
            String clientId,
            String clientSecret,
            boolean enabled,
            String updatedBy
    ) {
        public UpdateSharePointConnectionCommand {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(clientId, "clientId must not be null");
            Objects.requireNonNull(updatedBy, "updatedBy must not be null");
        }
    }

    record TestSharePointConnectionCommand(
            String tenantId,
            String clientId,
            String clientSecret
    ) {
        public TestSharePointConnectionCommand {
            Objects.requireNonNull(tenantId, "tenantId must not be null");
            Objects.requireNonNull(clientId, "clientId must not be null");
        }
    }
}
