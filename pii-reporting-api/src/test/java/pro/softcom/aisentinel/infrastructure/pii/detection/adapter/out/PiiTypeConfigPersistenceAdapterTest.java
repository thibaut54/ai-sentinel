package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiTypeConfigsPort.PiiTypeConfigUpdate;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.entity.PiiTypeConfigEntity;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa.PiiTypeConfigJpaRepository;

import java.time.LocalDateTime;
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

    @Nested
    class FindAll {

        @Test
        void Should_ReturnDomainList_When_EntitiesExist() {
            // Arrange
            PiiTypeConfigEntity entity = buildEntity("EMAIL", "GLINER");
            when(jpaRepository.findAll()).thenReturn(List.of(entity));

            // Act
            List<PiiTypeConfig> result = adapter.findAll();

            // Assert
            assertThat(result).hasSize(1);
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result.getFirst().getPiiType()).isEqualTo("EMAIL");
            softly.assertThat(result.getFirst().getDetector()).isEqualTo("GLINER");
            softly.assertThat(result.getFirst().isEnabled()).isTrue();
            softly.assertThat(result.getFirst().getThreshold()).isEqualTo(0.75);
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmptyList_When_NoEntitiesExist() {
            // Arrange
            when(jpaRepository.findAll()).thenReturn(List.of());

            // Act
            List<PiiTypeConfig> result = adapter.findAll();

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        void Should_MapAllEntities_When_MultipleEntitiesExist() {
            // Arrange
            PiiTypeConfigEntity entity1 = buildEntity("EMAIL", "GLINER");
            PiiTypeConfigEntity entity2 = buildEntity("PHONE", "PRESIDIO");
            when(jpaRepository.findAll()).thenReturn(List.of(entity1, entity2));

            // Act
            List<PiiTypeConfig> result = adapter.findAll();

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result).extracting(PiiTypeConfig::getPiiType)
                    .containsExactly("EMAIL", "PHONE");
        }
    }

    @Nested
    class FindByDetector {

        @Test
        void Should_ReturnFilteredList_When_DetectorMatches() {
            // Arrange
            PiiTypeConfigEntity entity = buildEntity("EMAIL", "GLINER");
            when(jpaRepository.findByDetector("GLINER")).thenReturn(List.of(entity));

            // Act
            List<PiiTypeConfig> result = adapter.findByDetector("GLINER");

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.getFirst().getDetector()).isEqualTo("GLINER");
        }

        @Test
        void Should_ReturnEmptyList_When_NoEntitiesMatchDetector() {
            // Arrange
            when(jpaRepository.findByDetector("UNKNOWN")).thenReturn(List.of());

            // Act
            List<PiiTypeConfig> result = adapter.findByDetector("UNKNOWN");

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        void Should_DelegateToJpaRepository_When_Called() {
            // Arrange
            when(jpaRepository.findByDetector("PRESIDIO")).thenReturn(List.of());

            // Act
            adapter.findByDetector("PRESIDIO");

            // Assert
            verify(jpaRepository).findByDetector("PRESIDIO");
        }
    }

    @Nested
    class FindByPiiTypeAndDetector {

        @Test
        void Should_ReturnConfig_When_Found() {
            // Arrange
            PiiTypeConfigEntity entity = buildEntity("EMAIL", "GLINER");
            when(jpaRepository.findByPiiTypeAndDetector("EMAIL", "GLINER"))
                    .thenReturn(Optional.of(entity));

            // Act
            Optional<PiiTypeConfig> result = adapter.findByPiiTypeAndDetector("EMAIL", "GLINER");

            // Assert
            assertThat(result).isPresent();
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result.get().getPiiType()).isEqualTo("EMAIL");
            softly.assertThat(result.get().getDetector()).isEqualTo("GLINER");
            softly.assertAll();
        }

        @Test
        void Should_ReturnEmpty_When_NotFound() {
            // Arrange
            when(jpaRepository.findByPiiTypeAndDetector("UNKNOWN", "GLINER"))
                    .thenReturn(Optional.empty());

            // Act
            Optional<PiiTypeConfig> result = adapter.findByPiiTypeAndDetector("UNKNOWN", "GLINER");

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        void Should_DelegateCorrectParameters_When_Called() {
            // Arrange
            when(jpaRepository.findByPiiTypeAndDetector("SSN", "REGEX"))
                    .thenReturn(Optional.empty());

            // Act
            adapter.findByPiiTypeAndDetector("SSN", "REGEX");

            // Assert
            verify(jpaRepository).findByPiiTypeAndDetector("SSN", "REGEX");
        }
    }

    @Nested
    class Save {

        @Test
        void Should_SaveAndReturnDomain_When_ValidConfig() {
            // Arrange
            PiiTypeConfig config = buildDomainConfig("EMAIL", "GLINER");
            PiiTypeConfigEntity savedEntity = buildEntity("EMAIL", "GLINER");
            when(jpaRepository.save(any(PiiTypeConfigEntity.class))).thenReturn(savedEntity);

            // Act
            PiiTypeConfig result = adapter.save(config);

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result.getPiiType()).isEqualTo("EMAIL");
            softly.assertThat(result.getDetector()).isEqualTo("GLINER");
            softly.assertThat(result.isEnabled()).isTrue();
            softly.assertThat(result.getThreshold()).isEqualTo(0.75);
            softly.assertAll();
        }

        @Test
        void Should_ConvertToEntityBeforeSaving_When_Called() {
            // Arrange
            PiiTypeConfig config = buildDomainConfig("PHONE", "PRESIDIO");
            PiiTypeConfigEntity savedEntity = buildEntity("PHONE", "PRESIDIO");
            when(jpaRepository.save(any(PiiTypeConfigEntity.class))).thenReturn(savedEntity);

            // Act
            adapter.save(config);

            // Assert
            verify(jpaRepository).save(any(PiiTypeConfigEntity.class));
        }

        @Test
        void Should_ReturnConvertedDomain_When_EntitySaved() {
            // Arrange
            PiiTypeConfig config = buildDomainConfig("SSN", "REGEX");
            PiiTypeConfigEntity savedEntity = buildEntity("SSN", "REGEX");
            savedEntity.setCategory("identification");
            savedEntity.setSeverity("HIGH");
            when(jpaRepository.save(any(PiiTypeConfigEntity.class))).thenReturn(savedEntity);

            // Act
            PiiTypeConfig result = adapter.save(config);

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result.getPiiType()).isEqualTo("SSN");
            softly.assertThat(result.getCategory()).isEqualTo("identification");
            softly.assertThat(result.getSeverity()).isEqualTo("HIGH");
            softly.assertAll();
        }
    }

    @Nested
    class SaveAll {

        @Test
        void Should_SaveAllAndReturnDomainList_When_MultipleConfigs() {
            // Arrange
            PiiTypeConfig config1 = buildDomainConfig("EMAIL", "GLINER");
            PiiTypeConfig config2 = buildDomainConfig("PHONE", "PRESIDIO");
            PiiTypeConfigEntity entity1 = buildEntity("EMAIL", "GLINER");
            PiiTypeConfigEntity entity2 = buildEntity("PHONE", "PRESIDIO");
            when(jpaRepository.saveAll(anyList())).thenReturn(List.of(entity1, entity2));

            // Act
            List<PiiTypeConfig> result = adapter.saveAll(List.of(config1, config2));

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result).extracting(PiiTypeConfig::getPiiType)
                    .containsExactly("EMAIL", "PHONE");
        }

        @Test
        void Should_ReturnEmptyList_When_EmptyInput() {
            // Arrange
            when(jpaRepository.saveAll(anyList())).thenReturn(List.of());

            // Act
            List<PiiTypeConfig> result = adapter.saveAll(List.of());

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        void Should_DelegateToJpaRepositorySaveAll_When_Called() {
            // Arrange
            PiiTypeConfig config = buildDomainConfig("EMAIL", "GLINER");
            PiiTypeConfigEntity entity = buildEntity("EMAIL", "GLINER");
            when(jpaRepository.saveAll(anyList())).thenReturn(List.of(entity));

            // Act
            adapter.saveAll(List.of(config));

            // Assert
            verify(jpaRepository).saveAll(anyList());
        }
    }

    @Nested
    class DeleteByPiiTypeAndDetector {

        @Test
        void Should_DelegateToJpaRepository_When_Called() {
            // Act
            adapter.deleteByPiiTypeAndDetector("EMAIL", "GLINER");

            // Assert
            verify(jpaRepository).deleteByPiiTypeAndDetector("EMAIL", "GLINER");
        }

        @Test
        void Should_PassCorrectParameters_When_Called() {
            // Act
            adapter.deleteByPiiTypeAndDetector("SSN", "REGEX");

            // Assert
            verify(jpaRepository).deleteByPiiTypeAndDetector("SSN", "REGEX");
        }

        @Test
        void Should_NotThrowException_When_EntityDoesNotExist() {
            // Act & Assert - no exception expected
            adapter.deleteByPiiTypeAndDetector("UNKNOWN", "UNKNOWN");
            verify(jpaRepository).deleteByPiiTypeAndDetector("UNKNOWN", "UNKNOWN");
        }
    }

    @Nested
    class Exists {

        @Test
        void Should_ReturnTrue_When_CountGreaterThanZero() {
            // Arrange
            when(jpaRepository.count()).thenReturn(5L);

            // Act
            boolean result = adapter.exists();

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        void Should_ReturnFalse_When_CountIsZero() {
            // Arrange
            when(jpaRepository.count()).thenReturn(0L);

            // Act
            boolean result = adapter.exists();

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        void Should_ReturnTrue_When_CountIsOne() {
            // Arrange
            when(jpaRepository.count()).thenReturn(1L);

            // Act
            boolean result = adapter.exists();

            // Assert
            assertThat(result).isTrue();
        }
    }

    @Nested
    class UpdateAtomically {

        @Test
        void Should_UpdateAndReturnConfig_When_EntityFound() {
            // Arrange
            PiiTypeConfigEntity entity = buildEntity("EMAIL", "GLINER");
            PiiTypeConfigEntity savedEntity = buildEntity("EMAIL", "GLINER");
            savedEntity.setEnabled(false);
            savedEntity.setThreshold(0.9);
            savedEntity.setUpdatedBy("admin");

            when(jpaRepository.findByPiiTypeAndDetector("EMAIL", "GLINER"))
                    .thenReturn(Optional.of(entity));
            when(jpaRepository.save(entity)).thenReturn(savedEntity);

            // Act
            PiiTypeConfig result = adapter.updateAtomically("EMAIL", "GLINER", false, 0.9, "admin");

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result.getPiiType()).isEqualTo("EMAIL");
            softly.assertThat(result.isEnabled()).isFalse();
            softly.assertThat(result.getThreshold()).isEqualTo(0.9);
            softly.assertThat(result.getUpdatedBy()).isEqualTo("admin");
            softly.assertAll();
        }

        @Test
        void Should_ThrowException_When_EntityNotFound() {
            // Arrange
            when(jpaRepository.findByPiiTypeAndDetector("UNKNOWN", "GLINER"))
                    .thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> adapter.updateAtomically("UNKNOWN", "GLINER", true, 0.5, "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Configuration not found for PII type: UNKNOWN and detector: GLINER");
        }

        @Test
        void Should_SetFieldsOnEntity_When_Updating() {
            // Arrange
            PiiTypeConfigEntity entity = buildEntity("PHONE", "PRESIDIO");
            PiiTypeConfigEntity savedEntity = buildEntity("PHONE", "PRESIDIO");
            savedEntity.setEnabled(true);
            savedEntity.setThreshold(0.85);
            savedEntity.setUpdatedBy("user1");

            when(jpaRepository.findByPiiTypeAndDetector("PHONE", "PRESIDIO"))
                    .thenReturn(Optional.of(entity));
            when(jpaRepository.save(entity)).thenReturn(savedEntity);

            // Act
            adapter.updateAtomically("PHONE", "PRESIDIO", true, 0.85, "user1");

            // Assert
            verify(jpaRepository).save(entity);
            assertThat(entity.isEnabled()).isTrue();
            assertThat(entity.getThreshold()).isEqualTo(0.85);
            assertThat(entity.getUpdatedBy()).isEqualTo("user1");
        }
    }

    @Nested
    class BulkUpdateAtomically {

        @Test
        void Should_UpdateAllAndReturnConfigs_When_AllEntitiesFound() {
            // Arrange
            PiiTypeConfigEntity entity1 = buildEntity("EMAIL", "GLINER");
            PiiTypeConfigEntity entity2 = buildEntity("PHONE", "PRESIDIO");
            PiiTypeConfigEntity savedEntity1 = buildEntity("EMAIL", "GLINER");
            savedEntity1.setEnabled(false);
            savedEntity1.setThreshold(0.8);
            PiiTypeConfigEntity savedEntity2 = buildEntity("PHONE", "PRESIDIO");
            savedEntity2.setEnabled(true);
            savedEntity2.setThreshold(0.6);

            when(jpaRepository.findByPiiTypeAndDetector("EMAIL", "GLINER"))
                    .thenReturn(Optional.of(entity1));
            when(jpaRepository.findByPiiTypeAndDetector("PHONE", "PRESIDIO"))
                    .thenReturn(Optional.of(entity2));
            when(jpaRepository.saveAll(anyList())).thenReturn(List.of(savedEntity1, savedEntity2));

            List<PiiTypeConfigUpdate> updates = List.of(
                    new PiiTypeConfigUpdate("EMAIL", "GLINER", false, 0.8),
                    new PiiTypeConfigUpdate("PHONE", "PRESIDIO", true, 0.6)
            );

            // Act
            List<PiiTypeConfig> result = adapter.bulkUpdateAtomically(updates, "admin");

            // Assert
            assertThat(result).hasSize(2);
            assertThat(result).extracting(PiiTypeConfig::getPiiType)
                    .containsExactly("EMAIL", "PHONE");
        }

        @Test
        void Should_ThrowException_When_AnyEntityNotFound() {
            // Arrange
            PiiTypeConfigEntity entity1 = buildEntity("EMAIL", "GLINER");
            when(jpaRepository.findByPiiTypeAndDetector("EMAIL", "GLINER"))
                    .thenReturn(Optional.of(entity1));
            when(jpaRepository.findByPiiTypeAndDetector("UNKNOWN", "PRESIDIO"))
                    .thenReturn(Optional.empty());

            List<PiiTypeConfigUpdate> updates = List.of(
                    new PiiTypeConfigUpdate("EMAIL", "GLINER", false, 0.8),
                    new PiiTypeConfigUpdate("UNKNOWN", "PRESIDIO", true, 0.6)
            );

            // Act & Assert
            assertThatThrownBy(() -> adapter.bulkUpdateAtomically(updates, "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Configuration not found for PII type: UNKNOWN and detector: PRESIDIO");
        }

        @Test
        void Should_SetUpdatedByOnAllEntities_When_BulkUpdating() {
            // Arrange
            PiiTypeConfigEntity entity1 = buildEntity("EMAIL", "GLINER");
            PiiTypeConfigEntity entity2 = buildEntity("PHONE", "PRESIDIO");

            when(jpaRepository.findByPiiTypeAndDetector("EMAIL", "GLINER"))
                    .thenReturn(Optional.of(entity1));
            when(jpaRepository.findByPiiTypeAndDetector("PHONE", "PRESIDIO"))
                    .thenReturn(Optional.of(entity2));
            when(jpaRepository.saveAll(anyList())).thenReturn(List.of(entity1, entity2));

            List<PiiTypeConfigUpdate> updates = List.of(
                    new PiiTypeConfigUpdate("EMAIL", "GLINER", false, 0.8),
                    new PiiTypeConfigUpdate("PHONE", "PRESIDIO", true, 0.6)
            );

            // Act
            adapter.bulkUpdateAtomically(updates, "batch-user");

            // Assert
            assertThat(entity1.getUpdatedBy()).isEqualTo("batch-user");
            assertThat(entity2.getUpdatedBy()).isEqualTo("batch-user");
            verify(jpaRepository).saveAll(anyList());
        }
    }

    // --- Test helpers ---

    private static PiiTypeConfigEntity buildEntity(String piiType, String detector) {
        PiiTypeConfigEntity entity = new PiiTypeConfigEntity();
        entity.setId(1L);
        entity.setPiiType(piiType);
        entity.setDetector(detector);
        entity.setEnabled(true);
        entity.setThreshold(0.75);
        entity.setCategory("contact");
        entity.setCountryCode("CH");
        entity.setDetectorLabel(piiType.toLowerCase());
        entity.setCustom(false);
        entity.setSeverity("MEDIUM");
        entity.setUpdatedAt(LocalDateTime.of(2026, 1, 15, 10, 0));
        entity.setUpdatedBy("system");
        return entity;
    }

    private static PiiTypeConfig buildDomainConfig(String piiType, String detector) {
        return PiiTypeConfig.builder()
                .id(1L)
                .piiType(piiType)
                .detector(detector)
                .enabled(true)
                .threshold(0.75)
                .category("contact")
                .countryCode("CH")
                .detectorLabel(piiType.toLowerCase())
                .custom(false)
                .severity("MEDIUM")
                .updatedAt(LocalDateTime.of(2026, 1, 15, 10, 0))
                .updatedBy("system")
                .build();
    }
}
