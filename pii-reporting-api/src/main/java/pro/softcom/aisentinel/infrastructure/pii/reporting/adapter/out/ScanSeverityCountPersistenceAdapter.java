package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanSeverityCountRepository;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ScanSeverityCount;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.ScanSeverityCountJpaRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanSeverityCountEntity;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanSeverityCountId;

import java.util.List;
import java.util.Optional;

/**
 * JPA adapter implementing {@link ScanSeverityCountRepository} port.
 *
 * <p>Bridges the hexagonal architecture boundary between application layer
 * (domain-focused use cases) and infrastructure layer (JPA persistence).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Maps domain objects (ScanSeverityCount, SeverityCounts) to/from JPA entities</li>
 *   <li>Delegates persistence operations to JPA repository</li>
 *   <li>Provides atomic increment operations via PostgreSQL UPSERT</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ScanSeverityCountPersistenceAdapter implements ScanSeverityCountRepository {

    private final ScanSeverityCountJpaRepository jpaRepository;

    @Override
    public void incrementCounts(String scanId, SourceType sourceType, String sourceKey, SeverityCounts delta) {
        jpaRepository.incrementCounts(
            scanId,
            sourceType.name(),
            sourceKey,
            delta.high(),
            delta.medium(),
            delta.low()
        );
    }

    @Override
    public Optional<SeverityCounts> findByScanIdAndSource(String scanId, SourceType sourceType, String sourceKey) {
        var id = ScanSeverityCountId.builder()
            .scanId(scanId)
            .sourceType(sourceType.name())
            .sourceKey(sourceKey)
            .build();

        return jpaRepository.findById(id)
            .map(this::toDomainCounts);
    }

    @Override
    public List<ScanSeverityCount> findByScanId(String scanId) {
        return jpaRepository.findById_ScanIdOrderById_SourceKey(scanId).stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public void deleteByScanId(String scanId) {
        jpaRepository.deleteById_ScanId(scanId);
    }

    @Override
    public void deleteBySourceType(SourceType sourceType) {
        if (sourceType == null) {
            return;
        }
        jpaRepository.deleteAllBySourceType(sourceType.name());
    }

    @Override
    public void deleteBySourceTypeAndSourceKeys(SourceType sourceType, List<String> sourceKeys) {
        if (sourceType == null || sourceKeys == null || sourceKeys.isEmpty()) {
            return;
        }
        jpaRepository.deleteAllBySourceTypeAndSourceKeys(sourceType.name(), sourceKeys);
    }

    /**
     * Maps JPA entity to domain SeverityCounts.
     */
    private SeverityCounts toDomainCounts(ScanSeverityCountEntity entity) {
        return new SeverityCounts(
            entity.getNbOfHighSeverity(),
            entity.getNbOfMediumSeverity(),
            entity.getNbOfLowSeverity()
        );
    }

    /**
     * Maps JPA entity to domain ScanSeverityCount.
     */
    private ScanSeverityCount toDomain(ScanSeverityCountEntity entity) {
        return new ScanSeverityCount(
            entity.getScanId(),
            SourceType.fromValue(entity.getSourceType()),
            entity.getSourceKey(),
            toDomainCounts(entity)
        );
    }
}
