package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointConnectionConfigRepository;
import pro.softcom.aisentinel.domain.sharepoint.SharePointConnectionSettings;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.jpa.SharePointConnectionConfigJpaRepository;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out.jpa.entity.SharePointConnectionConfigEntity;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence adapter for SharePoint connection configuration.
 * Implements database access using Spring Data JPA for single-row configuration pattern.
 */
@Component
@Slf4j
public class SharePointConnectionConfigPersistenceAdapter implements SharePointConnectionConfigRepository {

    private static final Integer CONFIG_ID = 1;

    private final SharePointConnectionConfigJpaRepository jpaRepository;

    public SharePointConnectionConfigPersistenceAdapter(SharePointConnectionConfigJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SharePointConnectionSettings> findSettings() {
        log.debug("Retrieving SharePoint connection configuration");
        return jpaRepository.findById(CONFIG_ID).map(this::toDomain);
    }

    @Override
    @Transactional
    public SharePointConnectionSettings save(SharePointConnectionSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }

        log.debug("Saving SharePoint connection configuration: tenantId={}, clientId={}, enabled={}, updatedBy={}",
                settings.tenantId(), settings.clientId(), settings.enabled(), settings.updatedBy());

        SharePointConnectionConfigEntity existing = jpaRepository.findById(CONFIG_ID).orElse(null);
        String existingSecret = existing != null ? existing.getClientSecretEncrypted() : "";

        SharePointConnectionConfigEntity entity = toEntity(settings, existingSecret);
        SharePointConnectionConfigEntity saved = jpaRepository.save(entity);

        log.info("SharePoint connection configuration saved successfully");
        return toDomain(saved);
    }

    @Override
    @Transactional
    public void saveEncryptedClientSecret(String encryptedClientSecret) {
        log.debug("Saving encrypted client secret for SharePoint");
        SharePointConnectionConfigEntity entity = jpaRepository.findById(CONFIG_ID)
                .orElseThrow(() -> new IllegalStateException("SharePoint configuration row not found"));
        entity.setClientSecretEncrypted(encryptedClientSecret != null ? encryptedClientSecret : "");
        jpaRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findEncryptedClientSecret() {
        return jpaRepository.findById(CONFIG_ID)
                .map(SharePointConnectionConfigEntity::getClientSecretEncrypted)
                .filter(secret -> !secret.isEmpty());
    }

    private SharePointConnectionSettings toDomain(SharePointConnectionConfigEntity entity) {
        return new SharePointConnectionSettings(
                entity.getId().longValue(),
                entity.getTenantId(),
                entity.getClientId(),
                entity.getEnabled(),
                entity.getUpdatedAt(),
                entity.getUpdatedBy()
        );
    }

    private SharePointConnectionConfigEntity toEntity(SharePointConnectionSettings settings, String encryptedSecret) {
        return SharePointConnectionConfigEntity.builder()
                .id(CONFIG_ID)
                .tenantId(settings.tenantId())
                .clientId(settings.clientId())
                .clientSecretEncrypted(encryptedSecret != null ? encryptedSecret : "")
                .enabled(settings.enabled())
                .updatedAt(settings.updatedAt() != null ? settings.updatedAt() : Instant.now())
                .updatedBy(settings.updatedBy())
                .build();
    }
}
