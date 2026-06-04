package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa.PiiTypeConfigJpaRepository;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("PII Type Config Consistency Integration Test")
class PiiTypeConfigConsistencyTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void registerDataSourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Ensure data.sql is executed
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @Autowired
    private PiiTypeConfigJpaRepository repository;

    @Test
    @DisplayName("Should_LoadAllDbValues_When_LoadingFromDataSql")
    void Should_LoadAllDbValues_When_LoadingFromDataSql() {
        // Act
        List<PiiTypeConfigEntity> allConfigs = repository.findAll();

        // Assert
        assertThat(allConfigs)
                .as("Database should not be empty")
                .isNotEmpty();

        // Check that we have coverage for the critical GLiNER detector
        List<String> glinerTypes = allConfigs.stream()
                .filter(config -> "GLINER".equals(config.getDetector()))
                .map(PiiTypeConfigEntity::getPiiType)
                .toList();

        assertThat(glinerTypes)
                .as("Should contain basic types")
                .contains("PERSON_NAME", "EMAIL", "PHONE_NUMBER");
    }

    @Test
    @DisplayName("Should_HaveNonBlankPiiType_When_CheckingAllEntries")
    void Should_HaveNonBlankPiiType_When_CheckingAllEntries() {
        // Act
        List<PiiTypeConfigEntity> allConfigs = repository.findAll();

        // Assert - all entries have non-blank piiType
        assertThat(allConfigs)
                .as("All config entries should have a non-blank piiType")
                .allSatisfy(config ->
                        assertThat(config.getPiiType()).isNotBlank()
                );

        // Verify we have distinct types for each detector
        Set<String> distinctTypes = allConfigs.stream()
                .map(PiiTypeConfigEntity::getPiiType)
                .collect(Collectors.toSet());

        assertThat(distinctTypes)
                .as("Database should contain multiple distinct PII types")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("Should_SeedGliner2Rows_When_LoadingFromDataSql")
    void Should_SeedGliner2Rows_When_LoadingFromDataSql() {
        List<PiiTypeConfigEntity> gliner2 = repository.findAll().stream()
                .filter(config -> "GLINER2".equals(config.getDetector()))
                .toList();

        assertThat(gliner2)
                .as("data.sql must seed GLINER2 rows")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Should_ApplyCategoryBasedEnabledDefaults_When_LoadingFromDataSql")
    void Should_ApplyCategoryBasedEnabledDefaults_When_LoadingFromDataSql() {
        // Product taxonomy: GOVERNMENT_ID / FINANCIAL / DIGITAL / SECURITY are enabled
        // by default; IDENTITY / CONTACT / TEMPORAL are disabled. The global
        // gliner2_enabled kill-switch stays FALSE per spec D4 (asserted on
        // pii_detection_config, not here), so nothing runs until an operator opts in.
        Set<String> enabledCategories = Set.of("GOVERNMENT_ID", "FINANCIAL", "DIGITAL", "SECURITY");

        List<PiiTypeConfigEntity> gliner2 = repository.findAll().stream()
                .filter(config -> "GLINER2".equals(config.getDetector()))
                .toList();

        assertThat(gliner2).allSatisfy(config -> {
            boolean expectedEnabled = enabledCategories.contains(config.getCategory());
            assertThat(config.isEnabled())
                    .as("GLINER2 row %s (category %s) enabled-by-default should be %s",
                            config.getPiiType(), config.getCategory(), expectedEnabled)
                    .isEqualTo(expectedEnabled);
        });
    }

    @Test
    @DisplayName("Should_PopulateDetectorDescription_When_Gliner2Seeded")
    void Should_PopulateDetectorDescription_When_Gliner2Seeded() {
        // Every GLINER2 row carries both a label and an inference description.
        List<PiiTypeConfigEntity> gliner2 = repository.findAll().stream()
                .filter(config -> "GLINER2".equals(config.getDetector()))
                .toList();

        assertThat(gliner2).allSatisfy(config -> {
            assertThat(config.getDetectorLabel())
                    .as("GLINER2 row %s must have a detector_label", config.getPiiType())
                    .isNotBlank();
            assertThat(config.getDetectorDescription())
                    .as("GLINER2 row %s must have a detector_description", config.getPiiType())
                    .isNotBlank();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PERSON", "FULL_NAME", "EMAIL", "PHONE_NUMBER", "POSTAL_CODE",
            "GOVERNMENT_ID", "PASSPORT_NUMBER", "TAX_NUMBER",
            "IBAN", "PAYMENT_CARD", "CARD_NUMBER", "CARD_CVV",
            "USERNAME", "IP_ADDRESS", "SENSITIVE_ACCOUNT_ID",
            "PASSWORD", "API_KEY", "ACCESS_TOKEN", "RECOVERY_CODE",
            "SENSITIVE_DATE", "EXPIRATION_DATE", "TRANSACTION_DATE"
    })
    @DisplayName("Should_MapGliner2TypeToLabelAndDescription_When_Seeded")
    void Should_MapGliner2TypeToLabelAndDescription_When_Seeded(String piiType) {
        // GLINER2 type mapping coherence across the full taxonomy (spec §9):
        // every seeded type carries a label, an inference description and the
        // recalibrated 0.50 threshold. Enabled-by-default is category-driven and
        // asserted separately.
        PiiTypeConfigEntity row = repository.findByPiiTypeAndDetector(piiType, "GLINER2")
                .orElseThrow(() -> new AssertionError(
                        "Missing GLINER2 seed row for pii_type=" + piiType));

        assertThat(row.getDetectorLabel()).isNotBlank();
        assertThat(row.getDetectorDescription()).isNotBlank();
        assertThat(row.getThreshold()).isEqualTo(0.50);
    }
}