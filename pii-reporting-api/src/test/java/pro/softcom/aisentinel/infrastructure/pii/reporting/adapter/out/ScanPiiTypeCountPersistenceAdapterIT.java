package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import jakarta.persistence.EntityManager;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pro.softcom.aisentinel.domain.pii.reporting.ScanPiiTypeCount;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.ScanPiiTypeCountJpaRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanPiiTypeCountEntity;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanPiiTypeCountId;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link ScanPiiTypeCountPersistenceAdapter}.
 *
 * <p>Tests the persistence layer with a real PostgreSQL database using Testcontainers.
 * Verifies the atomic UPSERT increment operation and query methods.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ScanPiiTypeCountPersistenceAdapter.class)
class ScanPiiTypeCountPersistenceAdapterIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void registerDataSourceProps(DynamicPropertyRegistry registry) {
        // Ensure container is started before Spring context builds
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
    private ScanPiiTypeCountJpaRepository jpaRepository;

    @Autowired
    private EntityManager em;

    @Autowired
    private ScanPiiTypeCountPersistenceAdapter adapter;

    @BeforeEach
    void cleanDb() {
        jpaRepository.deleteAll();
        em.flush();
    }

    @Test
    void Should_IncrementCounts_When_NewRecord() {
        // Act
        adapter.incrementCounts("scan-001", "SPACE-A", Map.of("EMAIL", 3, "IBAN_CODE", 2));

        // Assert
        var counts = adapter.findCountsByScanIdAndSpaceKey("scan-001", "SPACE-A");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(counts).containsEntry("EMAIL", 3);
        softly.assertThat(counts).containsEntry("IBAN_CODE", 2);
        softly.assertAll();
    }

    @Test
    void Should_IncrementCounts_When_ExistingRecord() {
        // Arrange - Create initial record
        var initialEntity = ScanPiiTypeCountEntity.builder()
            .id(ScanPiiTypeCountId.builder()
                .scanId("scan-002")
                .spaceKey("SPACE-B")
                .piiType("EMAIL")
                .build())
            .occurrenceCount(10)
            .build();
        em.persist(initialEntity);
        em.flush();
        em.clear();

        // Act - Increment with new delta
        adapter.incrementCounts("scan-002", "SPACE-B", Map.of("EMAIL", 5));

        // Assert - Verify atomic increment
        var counts = adapter.findCountsByScanIdAndSpaceKey("scan-002", "SPACE-B");
        assertThat(counts)
            .as("EMAIL count should be incremented from 10 by 5")
            .containsEntry("EMAIL", 15);
    }

    @Test
    void Should_SkipZeroDeltas_When_Increment() {
        // Act
        adapter.incrementCounts("scan-003", "SPACE-C", Map.of("EMAIL", 0, "PERSON", 4));

        // Assert - Only positive deltas are persisted
        var counts = adapter.findCountsByScanIdAndSpaceKey("scan-003", "SPACE-C");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(counts).doesNotContainKey("EMAIL");
        softly.assertThat(counts).containsEntry("PERSON", 4);
        softly.assertAll();
    }

    @Test
    void Should_FindCounts_ReturnEmpty_When_RecordNotExists() {
        // Act
        var counts = adapter.findCountsByScanIdAndSpaceKey("scan-999", "SPACE-NONE");

        // Assert
        assertThat(counts).isEmpty();
    }

    @Test
    void Should_FindByScanId_GroupBySpace_When_MultipleSpaces() {
        // Arrange
        adapter.incrementCounts("scan-005", "SPACE-A", Map.of("EMAIL", 1, "PERSON", 2));
        adapter.incrementCounts("scan-005", "SPACE-B", Map.of("IBAN_CODE", 3));
        em.flush();

        // Act
        var results = adapter.findByScanId("scan-005");

        // Assert - Grouped by space, ordered by space key
        assertThat(results).hasSize(2);
        assertThat(results)
            .extracting(ScanPiiTypeCount::spaceKey)
            .containsExactly("SPACE-A", "SPACE-B");
        assertThat(results.getFirst().countsByType())
            .containsEntry("EMAIL", 1)
            .containsEntry("PERSON", 2);
        assertThat(results.get(1).countsByType()).containsEntry("IBAN_CODE", 3);
    }

    @Test
    void Should_FindByScanId_ReturnEmptyList_When_NoRecords() {
        // Act
        var results = adapter.findByScanId("scan-999");

        // Assert
        assertThat(results).isEmpty();
    }

    @Test
    void Should_DeleteByScanId_RemoveAllRows_When_MultipleSpaces() {
        // Arrange
        adapter.incrementCounts("scan-008", "SPACE-A", Map.of("EMAIL", 1));
        adapter.incrementCounts("scan-008", "SPACE-B", Map.of("PERSON", 2));
        adapter.incrementCounts("scan-009", "SPACE-C", Map.of("IBAN_CODE", 3));
        em.flush();

        // Act
        adapter.deleteByScanId("scan-008");

        // Assert
        assertThat(adapter.findByScanId("scan-008")).isEmpty();
        assertThat(adapter.findByScanId("scan-009")).hasSize(1);
    }
}
