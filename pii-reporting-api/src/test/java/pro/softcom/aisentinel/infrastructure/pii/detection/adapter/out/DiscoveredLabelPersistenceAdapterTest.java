package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabel;
import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabelStatus;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.entity.DiscoveredLabelEntity;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa.DiscoveredLabelJpaRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveredLabelPersistenceAdapterTest {

    @Mock
    private DiscoveredLabelJpaRepository jpaRepository;

    @InjectMocks
    private DiscoveredLabelPersistenceAdapter adapter;

    @Test
    @DisplayName("Should_UpsertOncePerLabel_When_RecordOccurrences")
    void Should_UpsertOncePerLabel_When_RecordOccurrences() {
        Map<String, Integer> labelCounts = new LinkedHashMap<>();
        labelCounts.put("VEHICLE_COLOR", 2);
        labelCounts.put("PET_NAME", 1);

        adapter.recordOccurrences(labelCounts);

        verify(jpaRepository).upsertOccurrence("VEHICLE_COLOR", 2);
        verify(jpaRepository).upsertOccurrence("PET_NAME", 1);
    }

    @Test
    @DisplayName("Should_NotUpsert_When_RecordOccurrencesEmpty")
    void Should_NotUpsert_When_RecordOccurrencesEmpty() {
        adapter.recordOccurrences(Map.of());

        verifyNoInteractions(jpaRepository);
    }

    @Test
    @DisplayName("Should_MapEntitiesToDomain_When_FindByStatus")
    void Should_MapEntitiesToDomain_When_FindByStatus() {
        DiscoveredLabel domain = new DiscoveredLabel(
                "VEHICLE_COLOR", 5L, LocalDateTime.now(), LocalDateTime.now(), DiscoveredLabelStatus.PENDING);
        when(jpaRepository.findByStatus(DiscoveredLabelStatus.PENDING))
                .thenReturn(List.of(DiscoveredLabelEntity.fromDomain(domain)));

        List<DiscoveredLabel> result = adapter.findByStatus(DiscoveredLabelStatus.PENDING);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().label()).isEqualTo("VEHICLE_COLOR");
        assertThat(result.getFirst().status()).isEqualTo(DiscoveredLabelStatus.PENDING);
    }

    @Test
    @DisplayName("Should_ReturnEmptyList_When_FindByStatusReturnsNothing")
    void Should_ReturnEmptyList_When_FindByStatusReturnsNothing() {
        when(jpaRepository.findByStatus(DiscoveredLabelStatus.PENDING)).thenReturn(List.of());

        List<DiscoveredLabel> result = adapter.findByStatus(DiscoveredLabelStatus.PENDING);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should_UpdateStatusToPromoted_When_MarkPromoted")
    void Should_UpdateStatusToPromoted_When_MarkPromoted() {
        adapter.markPromoted("VEHICLE_COLOR");

        verify(jpaRepository).updateStatusByLabel("VEHICLE_COLOR", "PROMOTED");
    }

    @Test
    @DisplayName("Should_UpdateStatusToIgnored_When_MarkIgnored")
    void Should_UpdateStatusToIgnored_When_MarkIgnored() {
        adapter.markIgnored("MISC_LABEL");

        verify(jpaRepository).updateStatusByLabel("MISC_LABEL", "IGNORED");
    }
}
