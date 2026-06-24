package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.pii.reporting.ScanPiiTypeCount;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.ScanPiiTypeCountJpaRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanPiiTypeCountEntity;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanPiiTypeCountId;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanPiiTypeCountPersistenceAdapterTest {

    @Mock
    private ScanPiiTypeCountJpaRepository jpaRepository;

    @InjectMocks
    private ScanPiiTypeCountPersistenceAdapter adapter;

    private static final String SCAN_ID = "scan-123";
    private static final String SPACE_KEY = "SPACE";

    private ScanPiiTypeCountEntity buildEntity(String spaceKey, String piiType, int count) {
        ScanPiiTypeCountId id = ScanPiiTypeCountId.builder()
                .scanId(SCAN_ID)
                .spaceKey(spaceKey)
                .piiType(piiType)
                .build();
        return ScanPiiTypeCountEntity.builder()
                .id(id)
                .occurrenceCount(count)
                .build();
    }

    @Test
    void Should_CallRepositoryPerEntry_When_IncrementCounts() {
        Map<String, Integer> delta = new LinkedHashMap<>();
        delta.put("EMAIL", 3);
        delta.put("IBAN_CODE", 1);

        adapter.incrementCounts(SCAN_ID, SPACE_KEY, delta);

        verify(jpaRepository).incrementCounts(SCAN_ID, SPACE_KEY, "EMAIL", 3);
        verify(jpaRepository).incrementCounts(SCAN_ID, SPACE_KEY, "IBAN_CODE", 1);
    }

    @Test
    void Should_SkipNonPositiveAndNullEntries_When_IncrementCounts() {
        Map<String, Integer> delta = new HashMap<>();
        delta.put("EMAIL", 0);
        delta.put("PHONE_NUMBER", -2);
        delta.put("PERSON", null);
        delta.put("IBAN_CODE", 5);

        adapter.incrementCounts(SCAN_ID, SPACE_KEY, delta);

        verify(jpaRepository).incrementCounts(SCAN_ID, SPACE_KEY, "IBAN_CODE", 5);
        verify(jpaRepository, never()).incrementCounts(SCAN_ID, SPACE_KEY, "EMAIL", 0);
        verify(jpaRepository, never()).incrementCounts(SCAN_ID, SPACE_KEY, "PHONE_NUMBER", -2);
    }

    @Test
    void Should_DoNothing_When_DeltaIsNull() {
        adapter.incrementCounts(SCAN_ID, SPACE_KEY, null);

        verifyNoInteractions(jpaRepository);
    }

    @Test
    void Should_AggregateMap_When_FindCountsByScanIdAndSpaceKey() {
        when(jpaRepository.findById_ScanIdAndId_SpaceKey(SCAN_ID, SPACE_KEY))
                .thenReturn(List.of(
                        buildEntity(SPACE_KEY, "EMAIL", 4),
                        buildEntity(SPACE_KEY, "IBAN_CODE", 2)
                ));

        Map<String, Integer> result = adapter.findCountsByScanIdAndSpaceKey(SCAN_ID, SPACE_KEY);

        assertThat(result)
                .hasSize(2)
                .containsEntry("EMAIL", 4)
                .containsEntry("IBAN_CODE", 2);
    }

    @Test
    void Should_ReturnEmptyMap_When_FindCountsByScanIdAndSpaceKeyHasNoRows() {
        when(jpaRepository.findById_ScanIdAndId_SpaceKey(SCAN_ID, SPACE_KEY))
                .thenReturn(List.of());

        Map<String, Integer> result = adapter.findCountsByScanIdAndSpaceKey(SCAN_ID, SPACE_KEY);

        assertThat(result).isEmpty();
    }

    @Test
    void Should_GroupBySpace_When_FindByScanId() {
        when(jpaRepository.findById_ScanIdOrderById_SpaceKey(SCAN_ID))
                .thenReturn(List.of(
                        buildEntity("SPACE-A", "EMAIL", 2),
                        buildEntity("SPACE-A", "PERSON", 1),
                        buildEntity("SPACE-B", "IBAN_CODE", 3)
                ));

        List<ScanPiiTypeCount> result = adapter.findByScanId(SCAN_ID);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().scanId()).isEqualTo(SCAN_ID);
        assertThat(result.getFirst().spaceKey()).isEqualTo("SPACE-A");
        assertThat(result.getFirst().countsByType())
                .containsEntry("EMAIL", 2)
                .containsEntry("PERSON", 1);
        assertThat(result.get(1).spaceKey()).isEqualTo("SPACE-B");
        assertThat(result.get(1).countsByType()).containsEntry("IBAN_CODE", 3);
    }

    @Test
    void Should_ReturnEmptyList_When_FindByScanIdReturnsNothing() {
        when(jpaRepository.findById_ScanIdOrderById_SpaceKey(SCAN_ID))
                .thenReturn(List.of());

        List<ScanPiiTypeCount> result = adapter.findByScanId(SCAN_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void Should_CallRepository_When_DeleteByScanId() {
        adapter.deleteByScanId(SCAN_ID);

        verify(jpaRepository).deleteById_ScanId(SCAN_ID);
    }
}
