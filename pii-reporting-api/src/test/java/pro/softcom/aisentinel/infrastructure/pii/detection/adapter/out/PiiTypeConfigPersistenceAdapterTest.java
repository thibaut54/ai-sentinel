package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiTypeConfigsPort.PiiTypeConfigUpdate;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.entity.PiiTypeConfigEntity;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa.PiiTypeConfigJpaRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PiiTypeConfigPersistenceAdapterTest {

    @Mock
    private PiiTypeConfigJpaRepository jpaRepository;

    @InjectMocks
    private PiiTypeConfigPersistenceAdapter adapter;

    private PiiTypeConfigEntity buildEntity(String piiType, String detector) {
        PiiTypeConfig domain = PiiTypeConfig.builder()
                .piiType(piiType)
                .detector(detector)
                .enabled(true)
                .threshold(0.80)
                .category("CONTACT")
                .detectorLabel(piiType.toLowerCase())
                .severity("LOW")
                .build();
        return PiiTypeConfigEntity.fromDomain(domain);
    }

    @Test
    void Should_ReturnMappedList_When_FindAll() {
        when(jpaRepository.findAll()).thenReturn(List.of(buildEntity("EMAIL", "GLINER")));

        List<PiiTypeConfig> result = adapter.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getPiiType()).isEqualTo("EMAIL");
    }

    @Test
    void Should_ReturnEmptyList_When_FindAllReturnsNothing() {
        when(jpaRepository.findAll()).thenReturn(List.of());

        List<PiiTypeConfig> result = adapter.findAll();

        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnFilteredList_When_FindByDetector() {
        when(jpaRepository.findByDetector("PRESIDIO"))
                .thenReturn(List.of(buildEntity("CREDIT_CARD", "PRESIDIO")));

        List<PiiTypeConfig> result = adapter.findByDetector("PRESIDIO");

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getDetector()).isEqualTo("PRESIDIO");
    }

    @Test
    void Should_ReturnPresent_When_FindByPiiTypeAndDetectorExists() {
        when(jpaRepository.findByPiiTypeAndDetector("EMAIL", "GLINER"))
                .thenReturn(Optional.of(buildEntity("EMAIL", "GLINER")));

        Optional<PiiTypeConfig> result = adapter.findByPiiTypeAndDetector("EMAIL", "GLINER");

        assertThat(result).isPresent();
        assertThat(result.get().getPiiType()).isEqualTo("EMAIL");
    }

    @Test
    void Should_ReturnEmpty_When_FindByPiiTypeAndDetectorNotFound() {
        when(jpaRepository.findByPiiTypeAndDetector("UNKNOWN", "GLINER"))
                .thenReturn(Optional.empty());

        Optional<PiiTypeConfig> result = adapter.findByPiiTypeAndDetector("UNKNOWN", "GLINER");

        assertThat(result).isEmpty();
    }

    @Test
    void Should_SaveAndReturnDomain_When_Save() {
        PiiTypeConfig config = PiiTypeConfig.builder()
                .piiType("EMAIL").detector("GLINER").enabled(true)
                .threshold(0.80).category("CONTACT").detectorLabel("email").severity("LOW").build();
        PiiTypeConfigEntity savedEntity = buildEntity("EMAIL", "GLINER");
        when(jpaRepository.save(any())).thenReturn(savedEntity);

        PiiTypeConfig result = adapter.save(config);

        assertThat(result.getPiiType()).isEqualTo("EMAIL");
    }

    @Test
    void Should_ReturnTrue_When_ExistsAndCountIsPositive() {
        when(jpaRepository.count()).thenReturn(5L);

        boolean result = adapter.exists();

        assertThat(result).isTrue();
    }

    @Test
    void Should_ReturnFalse_When_ExistsAndCountIsZero() {
        when(jpaRepository.count()).thenReturn(0L);

        boolean result = adapter.exists();

        assertThat(result).isFalse();
    }

    @Test
    void Should_CallDeleteRepository_When_DeleteByPiiTypeAndDetector() {
        adapter.deleteByPiiTypeAndDetector("EMAIL", "GLINER");

        verify(jpaRepository).deleteByPiiTypeAndDetector("EMAIL", "GLINER");
    }

    @Test
    void Should_ThrowIllegalArgument_When_UpdateAtomicallyNotFound() {
        when(jpaRepository.findByPiiTypeAndDetector("UNKNOWN", "GLINER"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.updateAtomically(
                "UNKNOWN", "GLINER", true, 0.80, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Configuration not found");
    }

    @Test
    void Should_ReturnUpdated_When_UpdateAtomicallySucceeds() {
        PiiTypeConfigEntity entity = buildEntity("EMAIL", "GLINER");
        when(jpaRepository.findByPiiTypeAndDetector("EMAIL", "GLINER"))
                .thenReturn(Optional.of(entity));
        when(jpaRepository.save(entity)).thenReturn(entity);

        PiiTypeConfig result = adapter.updateAtomically("EMAIL", "GLINER", false, 0.90, "admin");

        assertThat(result).isNotNull();
    }

    @Test
    void Should_ReturnUpdated_When_UpdateAtomicallyWithDescriptionSucceeds() {
        PiiTypeConfigEntity entity = buildEntity("EMAIL", "GLINER");
        when(jpaRepository.findByPiiTypeAndDetector("EMAIL", "GLINER"))
                .thenReturn(Optional.of(entity));
        when(jpaRepository.save(entity)).thenReturn(entity);

        PiiTypeConfig result = adapter.updateAtomically(
                "EMAIL", "GLINER", false, 0.90, "new description", true, "admin");

        assertThat(result).isNotNull();
    }

    @Test
    void Should_ReturnBulkUpdated_When_BulkUpdateAtomically() {
        PiiTypeConfigEntity entity = buildEntity("EMAIL", "GLINER");
        when(jpaRepository.findByPiiTypeAndDetector("EMAIL", "GLINER"))
                .thenReturn(Optional.of(entity));
        when(jpaRepository.saveAll(anyList())).thenReturn(List.of(entity));

        List<PiiTypeConfigUpdate> updates = List.of(
                new PiiTypeConfigUpdate("EMAIL", "GLINER", true, 0.85, null, null)
        );

        List<PiiTypeConfig> result = adapter.bulkUpdateAtomically(updates, "admin");

        assertThat(result).hasSize(1);
    }

    @Test
    void Should_SaveAll_When_SaveAllCalled() {
        PiiTypeConfig config = PiiTypeConfig.builder()
                .piiType("EMAIL").detector("GLINER").enabled(true)
                .threshold(0.80).category("CONTACT").detectorLabel("email").severity("LOW").build();
        PiiTypeConfigEntity savedEntity = buildEntity("EMAIL", "GLINER");
        when(jpaRepository.saveAll(anyList())).thenReturn(List.of(savedEntity));

        List<PiiTypeConfig> result = adapter.saveAll(List.of(config));

        assertThat(result).hasSize(1);
    }
}
