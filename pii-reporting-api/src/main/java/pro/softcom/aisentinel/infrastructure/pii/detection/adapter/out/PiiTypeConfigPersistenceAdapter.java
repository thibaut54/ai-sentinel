package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiTypeConfigsPort.PiiTypeConfigUpdate;
import pro.softcom.aisentinel.application.pii.detection.port.out.PiiTypeConfigRepository;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.entity.PiiTypeConfigEntity;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.out.jpa.PiiTypeConfigJpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence adapter for PII type configurations.
 * <p>
 * Implements the repository port and delegates to Spring Data JPA repository.
 */
@Component
public class PiiTypeConfigPersistenceAdapter implements PiiTypeConfigRepository {

    private final PiiTypeConfigJpaRepository jpaRepository;

    public PiiTypeConfigPersistenceAdapter(PiiTypeConfigJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<PiiTypeConfig> findAll() {
        return jpaRepository.findAll().stream()
                .map(PiiTypeConfigEntity::toDomain)
                .toList();
    }

    @Override
    public List<PiiTypeConfig> findByDetector(String detector) {
        return jpaRepository.findByDetector(detector).stream()
                .map(PiiTypeConfigEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<PiiTypeConfig> findByPiiTypeAndDetector(String piiType, String detector) {
        return jpaRepository.findByPiiTypeAndDetector(piiType, detector)
                .map(PiiTypeConfigEntity::toDomain);
    }

    @Override
    public PiiTypeConfig save(PiiTypeConfig config) {
        PiiTypeConfigEntity entity = PiiTypeConfigEntity.fromDomain(config);
        PiiTypeConfigEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public List<PiiTypeConfig> saveAll(List<PiiTypeConfig> configs) {
        List<PiiTypeConfigEntity> entities = configs.stream()
                .map(PiiTypeConfigEntity::fromDomain)
                .toList();
        List<PiiTypeConfigEntity> saved = jpaRepository.saveAll(entities);
        return saved.stream()
                .map(PiiTypeConfigEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void deleteByPiiTypeAndDetector(String piiType, String detector) {
        jpaRepository.deleteByPiiTypeAndDetector(piiType, detector);
    }

    @Override
    public boolean exists() {
        return jpaRepository.count() > 0;
    }

    @Override
    @Transactional
    public PiiTypeConfig updateAtomically(
            String piiType,
            String detector,
            boolean enabled,
            double threshold,
            String updatedBy
    ) {
        return updateAtomically(piiType, detector, enabled, threshold, null, updatedBy);
    }

    @Override
    @Transactional
    public PiiTypeConfig updateAtomically(
            String piiType,
            String detector,
            boolean enabled,
            double threshold,
            String detectorDescription,
            String updatedBy
    ) {
        PiiTypeConfigEntity entity = jpaRepository.findByPiiTypeAndDetector(piiType, detector)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Configuration not found for PII type: " + piiType + " and detector: " + detector
                ));

        applyUpdate(entity, enabled, threshold, detectorDescription, updatedBy);

        PiiTypeConfigEntity saved = jpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    @Transactional
    public List<PiiTypeConfig> bulkUpdateAtomically(
            List<PiiTypeConfigUpdate> updates,
            String updatedBy
    ) {
        List<PiiTypeConfigEntity> entitiesToUpdate = updates.stream()
                .map(update -> {
                    PiiTypeConfigEntity entity = jpaRepository.findByPiiTypeAndDetector(
                            update.piiType(),
                            update.detector()
                    ).orElseThrow(() -> new IllegalArgumentException(
                            "Configuration not found for PII type: " + update.piiType() +
                                    " and detector: " + update.detector()
                    ));

                    applyUpdate(entity, update.enabled(), update.threshold(),
                            update.detectorDescription(), updatedBy);

                    return entity;
                })
                .toList();

        List<PiiTypeConfigEntity> saved = jpaRepository.saveAll(entitiesToUpdate);
        return saved.stream()
                .map(PiiTypeConfigEntity::toDomain)
                .toList();
    }

    /**
     * Applies an update to an entity in place.
     * <p>
     * "Absent = unchanged" semantics (spec §5.1): a {@code null}
     * {@code detectorDescription} leaves the stored description untouched, so a
     * client omitting the field never erases an existing description.
     */
    private void applyUpdate(
            PiiTypeConfigEntity entity,
            boolean enabled,
            double threshold,
            String detectorDescription,
            String updatedBy
    ) {
        entity.setEnabled(enabled);
        entity.setThreshold(threshold);
        if (detectorDescription != null) {
            entity.setDetectorDescription(detectorDescription);
        }
        entity.setUpdatedBy(updatedBy);
    }
}