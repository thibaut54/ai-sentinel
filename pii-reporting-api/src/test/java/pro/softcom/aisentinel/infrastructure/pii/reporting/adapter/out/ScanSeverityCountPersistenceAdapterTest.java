package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ScanSeverityCount;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.ScanSeverityCountJpaRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanSeverityCountEntity;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanSeverityCountId;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanSeverityCountPersistenceAdapterTest {

    @Mock
    private ScanSeverityCountJpaRepository jpaRepository;

    private ScanSeverityCountPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ScanSeverityCountPersistenceAdapter(jpaRepository);
    }

    @Test
    void Should_DelegateIncrementCounts_When_Called() {
        // Arrange
        SeverityCounts delta = new SeverityCounts(5, 10, 2);

        // Act
        adapter.incrementCounts("scan-1", SourceType.CONFLUENCE, "SPACE1", delta);

        // Assert
        verify(jpaRepository).incrementCounts("scan-1", "CONFLUENCE", "SPACE1", 5, 10, 2);
    }

    @Test
    void Should_ReturnSeverityCounts_When_EntityExists() {
        // Arrange
        ScanSeverityCountId id = ScanSeverityCountId.builder()
                .scanId("scan-1").sourceType("CONFLUENCE").sourceKey("SPACE1").build();
        ScanSeverityCountEntity entity = ScanSeverityCountEntity.builder()
                .id(id)
                .nbOfHighSeverity(5)
                .nbOfMediumSeverity(10)
                .nbOfLowSeverity(2)
                .build();
        when(jpaRepository.findById(any(ScanSeverityCountId.class))).thenReturn(Optional.of(entity));

        // Act
        Optional<SeverityCounts> result = adapter.findByScanIdAndSource("scan-1", SourceType.CONFLUENCE, "SPACE1");

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().high()).isEqualTo(5);
        assertThat(result.get().medium()).isEqualTo(10);
        assertThat(result.get().low()).isEqualTo(2);
    }

    @Test
    void Should_ReturnEmpty_When_EntityDoesNotExist() {
        // Arrange
        when(jpaRepository.findById(any(ScanSeverityCountId.class))).thenReturn(Optional.empty());

        // Act
        Optional<SeverityCounts> result = adapter.findByScanIdAndSource("scan-1", SourceType.CONFLUENCE, "SPACE1");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnDomainObjects_When_FindByScanId() {
        // Arrange
        ScanSeverityCountId id = ScanSeverityCountId.builder()
                .scanId("scan-1").sourceType("SHAREPOINT").sourceKey("site-1").build();
        ScanSeverityCountEntity entity = ScanSeverityCountEntity.builder()
                .id(id)
                .nbOfHighSeverity(3)
                .nbOfMediumSeverity(7)
                .nbOfLowSeverity(1)
                .build();
        when(jpaRepository.findById_ScanIdOrderById_SourceKey("scan-1")).thenReturn(List.of(entity));

        // Act
        List<ScanSeverityCount> result = adapter.findByScanId("scan-1");

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).scanId()).isEqualTo("scan-1");
        assertThat(result.get(0).sourceType()).isEqualTo(SourceType.SHAREPOINT);
        assertThat(result.get(0).sourceKey()).isEqualTo("site-1");
        assertThat(result.get(0).counts().high()).isEqualTo(3);
    }

    @Test
    void Should_ReturnEmptyList_When_NoEntitiesForScanId() {
        // Arrange
        when(jpaRepository.findById_ScanIdOrderById_SourceKey("scan-unknown")).thenReturn(List.of());

        // Act
        List<ScanSeverityCount> result = adapter.findByScanId("scan-unknown");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_DelegateDeleteByScanId_When_Called() {
        // Act
        adapter.deleteByScanId("scan-1");

        // Assert
        verify(jpaRepository).deleteById_ScanId("scan-1");
    }
}
