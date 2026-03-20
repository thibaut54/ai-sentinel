
package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ScanCheckpoint;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.DetectionCheckpointRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanCheckpointEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed implementation of ScanCheckpointRepository. Business intent: persist
 * fine-grained resume positions (content/attachment) per scan, source type and source key.
 * Now implemented with Spring Data JPA/Hibernate for simplicity and maintainability.
 */
@Component
@Slf4j
public class ScanCheckpointPersistenceAdapter implements ScanCheckpointRepository {

    private final DetectionCheckpointRepository jpaRepository;

    public ScanCheckpointPersistenceAdapter(DetectionCheckpointRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void save(ScanCheckpoint checkpoint) {
        if (checkpoint == null || isBlank(checkpoint.scanId()) || isBlank(checkpoint.sourceKey())) {
            return;
        }

        LocalDateTime lastUpdated = checkpoint.updatedAt() == null ? LocalDateTime.now() : checkpoint.updatedAt();
        String sourceTypeValue = checkpoint.sourceType() != null ? checkpoint.sourceType().name() : null;

        // Use PostgreSQL UPSERT (INSERT ... ON CONFLICT DO UPDATE) for atomic operation
        jpaRepository.upsertCheckpoint(
            checkpoint.scanId(),
            sourceTypeValue,
            checkpoint.sourceKey(),
            checkpoint.lastProcessedContentId(),
            checkpoint.lastProcessedAttachmentName(),
            checkpoint.scanStatus().name(),
            checkpoint.progressPercentage(),
            lastUpdated
        );
    }

    @Override
    public Optional<ScanCheckpoint> findByScanAndSource(String scanId, SourceType sourceType, String sourceKey) {
        if (isBlank(scanId) || sourceType == null || isBlank(sourceKey)) {
            return Optional.empty();
        }
        return jpaRepository.findByScanIdAndSourceTypeAndSourceKey(scanId, sourceType.name(), sourceKey)
            .map(ScanCheckpointPersistenceAdapter::toDomain);
    }

    @Override
    public List<ScanCheckpoint> findByScan(String scanId) {
        if (isBlank(scanId)) {
            return List.of();
        }
        return jpaRepository.findByScanIdOrderBySourceKey(scanId).stream()
            .map(ScanCheckpointPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<ScanCheckpoint> findBySource(SourceType sourceType, String sourceKey) {
        if (sourceType == null || isBlank(sourceKey)) {
            return List.of();
        }
        return jpaRepository.findBySourceTypeAndSourceKeyOrderByUpdatedAtDesc(sourceType.name(), sourceKey).stream()
            .map(ScanCheckpointPersistenceAdapter::toDomain).toList();
    }

    @Override
    public Optional<ScanCheckpoint> findLatestBySource(SourceType sourceType, String sourceKey) {
        if (sourceType == null || isBlank(sourceKey)) {
            return Optional.empty();
        }
        return jpaRepository.findFirstBySourceTypeAndSourceKeyOrderByUpdatedAtDesc(sourceType.name(), sourceKey)
            .map(ScanCheckpointPersistenceAdapter::toDomain);
    }

    @Override
    public List<ScanCheckpoint> findAllLatestCheckpoints() {
        return jpaRepository.findAllLatestCheckpoints().stream()
            .map(ScanCheckpointPersistenceAdapter::toDomain)
            .toList();
    }

    @Override
    public List<ScanCheckpoint> findAllLatestCheckpointsBySourceType(SourceType sourceType) {
        if (sourceType == null) {
            return List.of();
        }
        return jpaRepository.findAllLatestCheckpointsBySourceType(sourceType.name()).stream()
            .map(ScanCheckpointPersistenceAdapter::toDomain)
            .toList();
    }

    @Override
    public void deleteByScan(String scanId) {
        if (isBlank(scanId)) {
            return;
        }
        jpaRepository.deleteByScanId(scanId);
    }

    @Override
    @Transactional
    public void deleteActiveScanCheckpointsBySourceType(SourceType sourceType) {
        if (sourceType == null) {
            return;
        }
        log.info("[PURGE] Deleting active scan checkpoints (RUNNING/PAUSED status) for sourceType={}", sourceType);
        jpaRepository.deleteActiveScanCheckpointsBySourceType(sourceType.name());
        log.info("[PURGE] Active scan checkpoints deleted successfully for sourceType={}", sourceType);
    }

    @Override
    @Transactional
    public void deleteActiveScanCheckpointsForSources(SourceType sourceType, List<String> sourceKeys) {
        if (sourceType == null || sourceKeys == null || sourceKeys.isEmpty()) {
            return;
        }
        log.info("[PURGE] Deleting active scan checkpoints for sourceType={} and {} sources", sourceType, sourceKeys.size());
        jpaRepository.deleteActiveScanCheckpointsForSources(sourceType.name(), sourceKeys);
        log.info("[PURGE] Active scan checkpoints for sources deleted successfully");
    }

    @Override
    public Optional<ScanCheckpoint> findRunningScanCheckpoint(String scanId) {
        if (isBlank(scanId)) {
            return Optional.empty();
        }
        return jpaRepository.findRunningScanCheckpoint(scanId)
            .map(ScanCheckpointPersistenceAdapter::toDomain);
    }

    public static ScanCheckpoint toDomain(ScanCheckpointEntity e) {
        return ScanCheckpoint.builder()
            .scanId(e.getScanId())
            .sourceType(SourceType.fromValue(e.getSourceType()))
            .sourceKey(e.getSourceKey())
            .lastProcessedContentId(e.getLastProcessedContentId())
            .lastProcessedAttachmentName(e.getLastProcessedAttachmentName())
            .scanStatus(parseStatus(e.getStatus()))
            .progressPercentage(e.getProgressPercentage())
            .updatedAt(e.getUpdatedAt())
            .build();
    }

    private static ScanStatus parseStatus(String s) {
        return ScanStatus.valueOf(s);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
