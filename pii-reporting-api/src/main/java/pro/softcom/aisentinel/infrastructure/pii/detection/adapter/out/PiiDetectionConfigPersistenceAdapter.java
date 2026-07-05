package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.application.pii.detection.port.out.PiiDetectionConfigRepository;
import pro.softcom.aisentinel.domain.pii.detection.PiiDetectionConfig;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa.PiiDetectionConfigEntity;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa.PiiDetectionConfigJpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Persistence adapter for PII detection configuration.
 * Implements database access using Spring Data JPA for single-row configuration pattern.
 */
@Component
@Slf4j
public class PiiDetectionConfigPersistenceAdapter implements PiiDetectionConfigRepository {

    private static final Integer CONFIG_ID = 1;
    private static final int DEFAULT_MINISTRAL_CHUNK_SIZE = 2048;
    private static final int DEFAULT_MINISTRAL_OVERLAP = 410;

    private final PiiDetectionConfigJpaRepository jpaRepository;

    private final PiiDetectionConfigRepository self;

    public PiiDetectionConfigPersistenceAdapter(PiiDetectionConfigJpaRepository jpaRepository,
                                                @Lazy PiiDetectionConfigRepository self) {
        this.jpaRepository = jpaRepository;
        this.self = self;
    }

    @Override
    @Transactional
    public PiiDetectionConfig findConfig() {
        log.debug("Retrieving PII detection configuration");
        
        return jpaRepository.findById(CONFIG_ID)
                .map(this::toDomain)
                .orElseGet(() -> {
                    log.warn("Configuration not found, creating default configuration");
                    return createDefaultConfig();
                });
    }

    @Override
    @Transactional
    public void updateConfig(PiiDetectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Configuration cannot be null");
        }
        
        log.info("Updating PII detection configuration: presidioEnabled={}, " +
                "regexEnabled={}, ministralEnabled={}, threshold={}, postfilterEnabled={}, updatedBy={}",
                config.presidioEnabled(), config.regexEnabled(), config.ministralEnabled(),
                config.defaultThreshold(), config.postfilterEnabled(), config.updatedBy());


        PiiDetectionConfigEntity entity = toEntity(config);
        jpaRepository.save(entity);

        log.info("PII detection configuration updated successfully");
    }

    /**
     * Creates and persists default configuration.
     * Default: Presidio and regex enabled, threshold 0.75, post-filter OFF.
     */
    private PiiDetectionConfig createDefaultConfig() {
        log.info("Creating default PII detection configuration");

        PiiDetectionConfig defaultConfig = new PiiDetectionConfig(
                CONFIG_ID,
                true,  // presidioEnabled
                true,  // regexEnabled
                false, // ministralEnabled (explicit operator opt-in)
                DEFAULT_MINISTRAL_CHUNK_SIZE, // ministralChunkSize
                DEFAULT_MINISTRAL_OVERLAP, // ministralOverlap
                new BigDecimal("0.75"),  // defaultThreshold
                false, // postfilterEnabled (zero-effect rollout default)
                LocalDateTime.now(ZoneId.of("UTC")),
                "system"
        );

        self.updateConfig(defaultConfig);
        return defaultConfig;
    }

    /**
     * Maps JPA entity to domain model.
     */
    private PiiDetectionConfig toDomain(PiiDetectionConfigEntity entity) {
        return new PiiDetectionConfig(
                entity.getId(),
                entity.getPresidioEnabled(),
                entity.getRegexEnabled(),
                entity.getMinistralEnabled() != null && entity.getMinistralEnabled(),
                entity.getMinistralChunkSize() != null ? entity.getMinistralChunkSize() : DEFAULT_MINISTRAL_CHUNK_SIZE,
                entity.getMinistralOverlap() != null ? entity.getMinistralOverlap() : DEFAULT_MINISTRAL_OVERLAP,
                entity.getDefaultThreshold(),
                entity.getPostfilterEnabled() != null && entity.getPostfilterEnabled(),
                entity.getUpdatedAt(),
                entity.getUpdatedBy()
        );
    }

    /**
     * Maps domain model to JPA entity.
     */
    private PiiDetectionConfigEntity toEntity(PiiDetectionConfig config) {
        return PiiDetectionConfigEntity.builder()
                .id(CONFIG_ID)
                .presidioEnabled(config.presidioEnabled())
                .regexEnabled(config.regexEnabled())
                .ministralEnabled(config.ministralEnabled())
                .ministralChunkSize(config.ministralChunkSize())
                .ministralOverlap(config.ministralOverlap())
                .defaultThreshold(config.defaultThreshold())
                .postfilterEnabled(config.postfilterEnabled())
                .updatedAt(config.updatedAt() != null ? config.updatedAt() : LocalDateTime.now(ZoneId.of("UTC")))
                .updatedBy(config.updatedBy())
                .build();
    }
}