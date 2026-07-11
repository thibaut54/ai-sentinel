package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @InjectMocks
    private ScanSeverityCountPersistenceAdapter adapter;

    private static final String SCAN_ID = "scan-123";
    private static final String SPACE_KEY = "SPACE";

    private ScanSeverityCountEntity buildEntity(int high, int medium, int low) {
        ScanSeverityCountId id = ScanSeverityCountId.builder()
                .scanId(SCAN_ID)
                .spaceKey(SPACE_KEY)
                .build();
        return ScanSeverityCountEntity.builder()
                .id(id)
                .highSeverityCount(high)
                .mediumSeverityCount(medium)
                .lowSeverityCount(low)
                .build();
    }

    @Test
    void Should_CallRepository_When_IncrementCounts() {
        SeverityCounts delta = new SeverityCounts(3, 1, 2);

        adapter.incrementCounts(SCAN_ID, SPACE_KEY, delta);

        verify(jpaRepository).incrementCounts(SCAN_ID, SPACE_KEY, 3, 1, 2);
    }

    @Test
    void Should_ReturnPresent_When_FindByScanIdAndSpaceKeyExists() {
        when(jpaRepository.findById(any(ScanSeverityCountId.class)))
                .thenReturn(Optional.of(buildEntity(5, 3, 1)));

        Optional<SeverityCounts> result = adapter.findByScanIdAndSpaceKey(SCAN_ID, SPACE_KEY);

        assertThat(result).isPresent();
        assertThat(result.get().high()).isEqualTo(5);
        assertThat(result.get().medium()).isEqualTo(3);
        assertThat(result.get().low()).isOne();
    }

    @Test
    void Should_ReturnEmpty_When_FindByScanIdAndSpaceKeyNotExists() {
        when(jpaRepository.findById(any(ScanSeverityCountId.class)))
                .thenReturn(Optional.empty());

        Optional<SeverityCounts> result = adapter.findByScanIdAndSpaceKey(SCAN_ID, SPACE_KEY);

        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnMappedList_When_FindByScanId() {
        when(jpaRepository.findById_ScanIdOrderById_SpaceKey(SCAN_ID))
                .thenReturn(List.of(buildEntity(2, 0, 1)));

        List<ScanSeverityCount> result = adapter.findByScanId(SCAN_ID);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().scanId()).isEqualTo(SCAN_ID);
        assertThat(result.getFirst().spaceKey()).isEqualTo(SPACE_KEY);
        assertThat(result.getFirst().counts().high()).isEqualTo(2);
    }

    @Test
    void Should_ReturnEmptyList_When_FindByScanIdReturnsNothing() {
        when(jpaRepository.findById_ScanIdOrderById_SpaceKey(SCAN_ID))
                .thenReturn(List.of());

        List<ScanSeverityCount> result = adapter.findByScanId(SCAN_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void Should_CallRepository_When_DeleteByScanId() {
        adapter.deleteByScanId(SCAN_ID);

        verify(jpaRepository).deleteById_ScanId(SCAN_ID);
    }
}
