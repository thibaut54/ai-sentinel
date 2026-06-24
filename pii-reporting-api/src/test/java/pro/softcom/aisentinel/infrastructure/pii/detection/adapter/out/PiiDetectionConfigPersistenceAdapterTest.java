package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pro.softcom.aisentinel.AiSentinelApplication;
import pro.softcom.aisentinel.domain.pii.detection.PiiDetectionConfig;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa.PiiDetectionConfigEntity;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa.PiiDetectionConfigJpaRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
    classes = AiSentinelApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class PiiDetectionConfigPersistenceAdapterTest {

    private static final int CONFIG_ID = 1;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void registerDataSourceProps(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.properties.hibernate.dialect",
            () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private PiiDetectionConfigPersistenceAdapter persistenceAdapter;

    @Autowired
    private PiiDetectionConfigJpaRepository jpaRepository;

    @Test
    void Should_CreateAndPersistDefaultConfig_When_ConfigDoesNotExist() {
        jpaRepository.deleteAll();

        PiiDetectionConfig config = persistenceAdapter.findConfig();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(config).isNotNull();
        softly.assertThat(config.id()).isEqualTo(CONFIG_ID);
        softly.assertThat(config.glinerEnabled()).isTrue();
        softly.assertThat(config.presidioEnabled()).isTrue();
        softly.assertThat(config.regexEnabled()).isTrue();
        softly.assertThat(config.openmedEnabled()).isFalse();
        softly.assertThat(config.defaultThreshold())
            .isEqualByComparingTo(new BigDecimal("0.75"));
        softly.assertThat(config.llmJudgeEnabled()).isFalse();
        softly.assertThat(config.prefilterEnabled()).isFalse();
        softly.assertThat(config.updatedAt()).isNotNull();
        softly.assertThat(config.updatedBy()).isEqualTo("system");

        PiiDetectionConfigEntity entity = jpaRepository.findById(CONFIG_ID).orElse(null);
        softly.assertThat(entity).isNotNull();
        assertThat(entity).isNotNull();
        softly.assertThat(entity.getGlinerEnabled()).isTrue();
        softly.assertThat(entity.getPresidioEnabled()).isTrue();
        softly.assertThat(entity.getRegexEnabled()).isTrue();
        softly.assertThat(entity.getOpenmedEnabled()).isFalse();
        softly.assertThat(entity.getDefaultThreshold())
            .isEqualByComparingTo(new BigDecimal("0.75"));
        softly.assertThat(entity.getLlmJudgeEnabled()).isFalse();
        softly.assertThat(entity.getPrefilterEnabled()).isFalse();
        softly.assertThat(entity.getUpdatedAt()).isNotNull();
        softly.assertThat(entity.getUpdatedBy()).isEqualTo("system");

        softly.assertAll();
    }

    @Test
    void Should_UpdateAndReturnConfig_When_ConfigAlreadyExists() {
        jpaRepository.deleteAll();

        PiiDetectionConfig existingConfig = persistenceAdapter.findConfig();

        BigDecimal newThreshold = existingConfig.defaultThreshold()
            .add(new BigDecimal("0.10"));
        LocalDateTime updateTime = LocalDateTime.now();

        PiiDetectionConfig updatedConfig = new PiiDetectionConfig(
            existingConfig.id(),
            false,
            existingConfig.presidioEnabled(),
            existingConfig.regexEnabled(),
            existingConfig.openmedEnabled(), false,
            false, 1024, 128,
            newThreshold,
            30,
            false,
            false, false, false, false, false,
            false,
            updateTime,
            "integration-test"
        );

        persistenceAdapter.updateConfig(updatedConfig);

        PiiDetectionConfig reloadedConfig = persistenceAdapter.findConfig();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(reloadedConfig.id()).isEqualTo(CONFIG_ID);
        softly.assertThat(reloadedConfig.glinerEnabled()).isFalse();
        softly.assertThat(reloadedConfig.presidioEnabled())
            .isEqualTo(existingConfig.presidioEnabled());
        softly.assertThat(reloadedConfig.regexEnabled())
            .isEqualTo(existingConfig.regexEnabled());
        softly.assertThat(reloadedConfig.defaultThreshold())
            .isEqualByComparingTo(newThreshold);
        softly.assertThat(reloadedConfig.updatedAt()).isNotNull();
        softly.assertThat(reloadedConfig.updatedBy()).isEqualTo("integration-test");

        PiiDetectionConfigEntity entity = jpaRepository.findById(CONFIG_ID).orElse(null);
        softly.assertThat(entity).isNotNull();
        assertThat(entity).isNotNull();
        softly.assertThat(entity.getGlinerEnabled()).isFalse();
        softly.assertThat(entity.getDefaultThreshold())
            .isEqualByComparingTo(newThreshold);
        softly.assertThat(entity.getUpdatedBy()).isEqualTo("integration-test");

        softly.assertAll();
    }

    @Test
    void Should_PersistAndRetrieveLlmJudgeEnabled_When_FlagIsEnabled() {
        jpaRepository.deleteAll();

        PiiDetectionConfig enabledConfig = new PiiDetectionConfig(
            CONFIG_ID,
            true,
            true,
            true,
            false, false, false, 1024, 128, new BigDecimal("0.75"),
            30,
            true,
            true, false, false, false, false,
            false,
            LocalDateTime.now(),
            "judge-enabler"
        );

        persistenceAdapter.updateConfig(enabledConfig);

        PiiDetectionConfig reloaded = persistenceAdapter.findConfig();
        PiiDetectionConfigEntity entity = jpaRepository.findById(CONFIG_ID).orElseThrow();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(reloaded.llmJudgeEnabled()).isTrue();
        softly.assertThat(entity.getLlmJudgeEnabled()).isTrue();
        softly.assertAll();
    }

    @Test
    void Should_PersistAndRetrievePrefilterEnabled_When_FlagIsEnabled() {
        jpaRepository.deleteAll();

        PiiDetectionConfig enabledConfig = new PiiDetectionConfig(
            CONFIG_ID,
            true,
            true,
            true,
            false, false, false, 1024, 128, new BigDecimal("0.75"),
            30,
            false,
            false, false, false, false, false,
            true,
            LocalDateTime.now(),
            "prefilter-enabler"
        );

        persistenceAdapter.updateConfig(enabledConfig);

        PiiDetectionConfig reloaded = persistenceAdapter.findConfig();
        PiiDetectionConfigEntity entity = jpaRepository.findById(CONFIG_ID).orElseThrow();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(reloaded.prefilterEnabled()).isTrue();
        softly.assertThat(entity.getPrefilterEnabled()).isTrue();
        softly.assertAll();
    }

    @Test
    void Should_RoundTripPerDetectorJudgeFlags_When_Persisted() {
        jpaRepository.deleteAll();

        PiiDetectionConfig config = new PiiDetectionConfig(
            CONFIG_ID,
            true, true, true, false, false, false, 1024, 128, new BigDecimal("0.75"),
            30,
            true,
            true, false, true, false, true,
            false,
            LocalDateTime.now(),
            "per-detector-judge"
        );

        persistenceAdapter.updateConfig(config);

        PiiDetectionConfig reloaded = persistenceAdapter.findConfig();
        PiiDetectionConfigEntity entity = jpaRepository.findById(CONFIG_ID).orElseThrow();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(reloaded.glinerJudgeEnabled()).isTrue();
        softly.assertThat(reloaded.presidioJudgeEnabled()).isFalse();
        softly.assertThat(reloaded.regexJudgeEnabled()).isTrue();
        softly.assertThat(reloaded.openmedJudgeEnabled()).isFalse();
        softly.assertThat(reloaded.gliner2JudgeEnabled()).isTrue();
        softly.assertThat(entity.getGlinerJudgeEnabled()).isTrue();
        softly.assertThat(entity.getPresidioJudgeEnabled()).isFalse();
        softly.assertThat(entity.getRegexJudgeEnabled()).isTrue();
        softly.assertThat(entity.getOpenmedJudgeEnabled()).isFalse();
        softly.assertThat(entity.getGliner2JudgeEnabled()).isTrue();
        softly.assertAll();
    }

    @Test
    void Should_DefaultPerDetectorJudgeFlagsToFalse_When_DefaultConfigCreated() {
        jpaRepository.deleteAll();

        PiiDetectionConfig config = persistenceAdapter.findConfig();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(config.glinerJudgeEnabled()).isFalse();
        softly.assertThat(config.presidioJudgeEnabled()).isFalse();
        softly.assertThat(config.regexJudgeEnabled()).isFalse();
        softly.assertThat(config.openmedJudgeEnabled()).isFalse();
        softly.assertThat(config.gliner2JudgeEnabled()).isFalse();
        softly.assertThat(config.llmJudgeEnabled()).isFalse();
        softly.assertAll();
    }

    @Test
    void Should_RoundTripMinistralFields_When_Persisted() {
        jpaRepository.deleteAll();

        PiiDetectionConfig config = new PiiDetectionConfig(
            CONFIG_ID,
            true, false, false, false, false, true, 2048, 256, new BigDecimal("0.75"),
            30,
            false,
            false, false, false, false, false,
            false,
            LocalDateTime.now(),
            "ministral-enabler"
        );

        persistenceAdapter.updateConfig(config);

        PiiDetectionConfig reloaded = persistenceAdapter.findConfig();
        PiiDetectionConfigEntity entity = jpaRepository.findById(CONFIG_ID).orElseThrow();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(reloaded.ministralEnabled()).isTrue();
        softly.assertThat(reloaded.ministralChunkSize()).isEqualTo(2048);
        softly.assertThat(reloaded.ministralOverlap()).isEqualTo(256);
        softly.assertThat(entity.getMinistralEnabled()).isTrue();
        softly.assertThat(entity.getMinistralChunkSize()).isEqualTo(2048);
        softly.assertThat(entity.getMinistralOverlap()).isEqualTo(256);
        // The adapter force-sets the DB-only judge column to false.
        softly.assertThat(entity.getMinistralJudgeEnabled()).isFalse();
        softly.assertAll();
    }
}
