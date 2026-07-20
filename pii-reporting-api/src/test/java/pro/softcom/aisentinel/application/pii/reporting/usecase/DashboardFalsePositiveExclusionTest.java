package pro.softcom.aisentinel.application.pii.reporting.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceSpaceRepository;
import pro.softcom.aisentinel.application.pii.detection.port.out.PiiTypeConfigRepository;
import pro.softcom.aisentinel.application.pii.remediation.port.out.FindingRemediationStore;
import pro.softcom.aisentinel.application.pii.remediation.service.ScanEventFindingResolver;
import pro.softcom.aisentinel.application.pii.reporting.ScanPiiTypeCountService;
import pro.softcom.aisentinel.application.pii.reporting.ScanSeverityCountService;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;
import pro.softcom.aisentinel.application.pii.reporting.service.FalsePositiveDetectionFilter;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.application.pii.security.PiiAccessAuditService;
import pro.softcom.aisentinel.application.pii.security.ScanResultEncryptor;
import pro.softcom.aisentinel.application.pii.security.port.out.SavePiiAuditPort;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediation;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRemediationStatus;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.DetectedPersonallyIdentifiableInformation;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;
import pro.softcom.aisentinel.domain.pii.reporting.ScanCheckpoint;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;
import pro.softcom.aisentinel.domain.pii.security.EncryptionMetadata;
import pro.softcom.aisentinel.domain.pii.security.EncryptionService;
import pro.softcom.aisentinel.domain.pii.security.PiiAuditRecord;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.JpaScanResultQueryAdapter;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.ScanCheckpointPersistenceAdapter;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.DetectionEventRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanEventEntity;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.JpaFindingRemediationAdapter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;

/**
 * End-to-end persistence proof that a finding flagged {@code FALSE_POSITIVE} disappears from the
 * dashboard read path: it is excluded from the item detail and its occurrences are reported as a
 * severity delta so the counters decrement. Exercises the real scan-event persistence, the real
 * remediation projection and the identity join between the two contexts.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    JpaScanResultQueryAdapter.class,
    ScanCheckpointPersistenceAdapter.class,
    JpaFindingRemediationAdapter.class,
    DashboardFalsePositiveExclusionTest.TestEncryptionConfig.class
})
class DashboardFalsePositiveExclusionTest {

    private static final String SCAN_ID = "scan-fp-it";
    private static final String SPACE = "SPACE-FP";
    private static final String PAGE = "page-1";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void registerDataSourceProps(DynamicPropertyRegistry registry) {
        postgres.start();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Autowired
    private DetectionEventRepository detectionEventRepository;

    @Autowired
    private ScanResultQuery scanResultQuery;

    @Autowired
    private ScanCheckpointRepository scanCheckpointRepository;

    @Autowired
    private FindingRemediationStore findingRemediationStore;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SeverityCalculationService severityCalculationService =
            new SeverityCalculationService(mock(PiiTypeConfigRepository.class));
    private final ScanEventFindingResolver resolver =
            new ScanEventFindingResolver(severityCalculationService);

    private FalsePositiveDetectionFilter falsePositiveFilter;
    private ScanReportingUseCase scanReportingUseCase;

    @TestConfiguration
    static class TestEncryptionConfig {

        @Bean
        EncryptionService testEncryptionService() {
            return new EncryptionService() {
                @Override
                public String encrypt(String plaintext, EncryptionMetadata metadata) {
                    return plaintext;
                }

                @Override
                public String decrypt(String ciphertext, EncryptionMetadata metadata) {
                    return ciphertext;
                }

                @Override
                public boolean isEncrypted(String value) {
                    return false;
                }
            };
        }

        @Bean
        ScanResultEncryptor testScanResultEncryptor(EncryptionService testEncryptionService) {
            return new ScanResultEncryptor(testEncryptionService);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        PiiAccessAuditService testPiiAccessAuditService() {
            SavePiiAuditPort savePiiAuditPort = new SavePiiAuditPort() {
                @Override
                public void save(PiiAuditRecord auditRecord) {
                    // no-op for integration tests
                }

                @Override
                public int deleteExpiredRecords(Instant expirationDate) {
                    return 0;
                }
            };
            return new PiiAccessAuditService(savePiiAuditPort, 365);
        }
    }

    @BeforeEach
    void setUp() {
        falsePositiveFilter = new FalsePositiveDetectionFilter(
                findingRemediationStore, resolver, severityCalculationService, scanResultQuery);
        scanReportingUseCase = new ScanReportingUseCase(scanResultQuery, scanCheckpointRepository,
                mock(ConfluenceSpaceRepository.class), mock(ScanSeverityCountService.class),
                mock(ScanPiiTypeCountService.class), falsePositiveFilter);
        detectionEventRepository.deleteAll();
    }

    @Test
    void Should_HideFalsePositiveFindingFromDashboard_When_FlaggedAndPersisted() {
        DetectedPersonallyIdentifiableInformation emailOccurrence1 =
                detection("EMAIL", "fp-email", DetectorSource.PRESIDIO, 0, 5);
        DetectedPersonallyIdentifiableInformation emailOccurrence2 =
                detection("EMAIL", "fp-email", DetectorSource.PRESIDIO, 40, 45);
        DetectedPersonallyIdentifiableInformation iban =
                detection("IBAN", "fp-iban", DetectorSource.REGEX, 10, 30);
        ConfluenceContentScanResult event = itemEvent(List.of(emailOccurrence1, emailOccurrence2, iban));
        String emailFindingId = resolver.stableFindingId(event, emailOccurrence1);

        persist(event);
        findingRemediationStore.upsertAll(List.of(falsePositiveRow(emailFindingId)));

        List<ConfluenceContentScanResult> items = scanReportingUseCase.getGlobalScanItemsEncrypted();
        SeverityCounts delta = falsePositiveFilter.falsePositiveDelta(SCAN_ID, SPACE);

        assertThat(items).hasSize(1);
        ConfluenceContentScanResult filtered = items.getFirst();
        assertSoftly(softly -> {
            // The EMAIL false positive (both occurrences) is gone; the genuine IBAN remains.
            softly.assertThat(filtered.detectedPIIs()).hasSize(1);
            softly.assertThat(filtered.detectedPIIs().getFirst().piiType()).isEqualTo("IBAN");
            softly.assertThat(filtered.detectedPiiCountByType()).isEqualTo(Map.of("IBAN", 1));
            softly.assertThat(filtered.detectedPiiCountBySeverity())
                    .containsEntry("high", 1).containsEntry("low", 0);
            // Both EMAIL occurrences (LOW) contribute to the counter decrement, the IBAN does not.
            softly.assertThat(delta.low()).isEqualTo(2);
            softly.assertThat(delta.high()).isZero();
        });
    }

    @Test
    void Should_ReappearOnDashboard_When_FalsePositiveRestoredToPending() {
        DetectedPersonallyIdentifiableInformation email =
                detection("EMAIL", "fp-email", DetectorSource.PRESIDIO, 0, 5);
        ConfluenceContentScanResult event = itemEvent(List.of(email));
        String emailFindingId = resolver.stableFindingId(event, email);
        persist(event);
        findingRemediationStore.upsertAll(List.of(falsePositiveRow(emailFindingId)));

        // Item entirely false positive -> dropped from the dashboard.
        assertThat(scanReportingUseCase.getGlobalScanItemsEncrypted()).isEmpty();

        // Restore to PENDING (removes the FALSE_POSITIVE projection).
        findingRemediationStore.upsertAll(List.of(
                falsePositiveRow(emailFindingId).toBuilder()
                        .status(FindingRemediationStatus.PENDING)
                        .build()));

        List<ConfluenceContentScanResult> items = scanReportingUseCase.getGlobalScanItemsEncrypted();
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().detectedPIIs()).hasSize(1);
    }

    private void persist(ConfluenceContentScanResult event) {
        ScanEventEntity entity = ScanEventEntity.builder()
                .scanId(SCAN_ID)
                .eventSeq(1L)
                .spaceKey(SPACE)
                .eventType("item")
                .occurredAt(Instant.parse("2026-01-01T10:00:00Z"))
                .pageId(PAGE)
                .pageTitle("Page 1")
                .payload(objectMapper.valueToTree(event))
                .build();
        detectionEventRepository.save(entity);
        scanCheckpointRepository.save(ScanCheckpoint.builder()
                .scanId(SCAN_ID)
                .spaceKey(SPACE)
                .scanStatus(ScanStatus.COMPLETED)
                .updatedAt(LocalDateTime.of(2026, Month.JANUARY, 1, 10, 0))
                .progressPercentage(100.0)
                .build());
    }

    private ConfluenceContentScanResult itemEvent(List<DetectedPersonallyIdentifiableInformation> detections) {
        return ConfluenceContentScanResult.builder()
                .scanId(SCAN_ID)
                .spaceKey(SPACE)
                .eventType("item")
                .isFinal(true)
                .pageId(PAGE)
                .pageTitle("Page 1")
                .detectedPIIs(detections)
                .build();
    }

    private static DetectedPersonallyIdentifiableInformation detection(String piiType, String fingerprint,
                                                                       DetectorSource source, int start, int end) {
        return DetectedPersonallyIdentifiableInformation.builder()
                .startPosition(start)
                .endPosition(end)
                .piiType(piiType)
                .piiTypeLabel(piiType)
                .confidence(0.9)
                .maskedContext("masked-" + piiType)
                .source(source)
                .valueFingerprint(fingerprint)
                .build();
    }

    private static FindingRemediation falsePositiveRow(String findingId) {
        return FindingRemediation.builder()
                .findingId(findingId)
                .scanId(SCAN_ID)
                .spaceKey(SPACE)
                .pageId(PAGE)
                .piiType("EMAIL")
                .severity(PersonallyIdentifiableInformationSeverity.LOW)
                .detector("PRESIDIO")
                .status(FindingRemediationStatus.FALSE_POSITIVE)
                .actor("reviewer")
                .occurredAt(Instant.parse("2026-01-01T10:00:00Z"))
                .build();
    }
}
