package pro.softcom.aisentinel.application.sharepoint.port.out;

import pro.softcom.aisentinel.domain.sharepoint.SharePointConnectionSettings;

import java.util.Optional;

/**
 * Port OUT for SharePoint connection configuration persistence.
 * Defines repository operations for SharePoint connection settings.
 */
public interface SharePointConnectionConfigRepository {
    Optional<SharePointConnectionSettings> findSettings();
    SharePointConnectionSettings save(SharePointConnectionSettings settings);
    void saveEncryptedClientSecret(String plainClientSecret);
    Optional<String> findEncryptedClientSecret();
}
