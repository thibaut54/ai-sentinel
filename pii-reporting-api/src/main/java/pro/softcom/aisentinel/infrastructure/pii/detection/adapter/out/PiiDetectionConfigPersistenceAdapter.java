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

/**
 * Persistence adapter for PII detection configuration.
 * Implements database access using Spring Data JPA for single-row configuration pattern.
 */
@Component
@Slf4j
public class PiiDetectionConfigPersistenceAdapter implements PiiDetectionConfigRepository {

    private static final Integer CONFIG_ID = 1;

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
        
        log.info("Updating PII detection configuration: glinerEnabled={}, presidioEnabled={}, " +
                "regexEnabled={}, openmedEnabled={}, gliner2Enabled={}, threshold={}, nbOfLabelByPass={}, llmJudgeEnabled={}, prefilterEnabled={}, updatedBy={}",
                config.glinerEnabled(), config.presidioEnabled(),
                config.regexEnabled(), config.openmedEnabled(), config.gliner2Enabled(),
                config.defaultThreshold(), config.nbOfLabelByPass(), config.llmJudgeEnabled(),
                config.prefilterEnabled(), config.updatedBy());


        PiiDetectionConfigEntity entity = toEntity(config);
        jpaRepository.save(entity);

        log.info("PII detection configuration updated successfully");
    }

    /**
     * Creates and persists default configuration.
     * Default: All detectors enabled, threshold 0.75, LLM judge OFF, pre-filter OFF.
     */
    private PiiDetectionConfig createDefaultConfig() {
        log.info("Creating default PII detection configuration");

        PiiDetectionConfig defaultConfig = new PiiDetectionConfig(
                CONFIG_ID,
                true,  // glinerEnabled
                true,  // presidioEnabled
                true,  // regexEnabled
                false, // openmedEnabled
                false, // gliner2Enabled (cf. spec D4 — explicit operator opt-in)
                new BigDecimal("0.75"),  // defaultThreshold
                35, // nbOfLabelByPass
                false, // llmJudgeEnabled (cf. spec §1.4 — zero-effect MVP default)
                false, // prefilterEnabled (zero-effect rollout default)
                LocalDateTime.now(),
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
                entity.getGlinerEnabled(),
                entity.getPresidioEnabled(),
                entity.getRegexEnabled(),
                entity.getOpenmedEnabled() != null && entity.getOpenmedEnabled(),
                entity.getGliner2Enabled() != null && entity.getGliner2Enabled(),
                entity.getDefaultThreshold(),
                entity.getNbOfLabelByPass() != null ? entity.getNbOfLabelByPass() : 35,
                entity.getLlmJudgeEnabled() != null && entity.getLlmJudgeEnabled(),
                entity.getPrefilterEnabled() != null && entity.getPrefilterEnabled(),
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
                .glinerEnabled(config.glinerEnabled())
                .presidioEnabled(config.presidioEnabled())
                .regexEnabled(config.regexEnabled())
                .openmedEnabled(config.openmedEnabled())
                .gliner2Enabled(config.gliner2Enabled())
                .defaultThreshold(config.defaultThreshold())
                .nbOfLabelByPass(config.nbOfLabelByPass())
                .llmJudgeEnabled(config.llmJudgeEnabled())
                .prefilterEnabled(config.prefilterEnabled())
                .updatedAt(config.updatedAt() != null ? config.updatedAt() : LocalDateTime.now())
                .updatedBy(config.updatedBy())
                .build();
    }
}