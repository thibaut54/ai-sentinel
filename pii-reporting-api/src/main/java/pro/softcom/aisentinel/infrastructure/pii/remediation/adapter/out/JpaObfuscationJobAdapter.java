package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pro.softcom.aisentinel.application.pii.remediation.port.out.ObfuscationJobStore;
import org.springframework.dao.DataIntegrityViolationException;
import pro.softcom.aisentinel.domain.pii.remediation.FindingRedactionOutcome;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJob;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJobAlreadyRunningException;
import pro.softcom.aisentinel.domain.pii.remediation.ObfuscationJobStatus;
import pro.softcom.aisentinel.domain.pii.remediation.RemediationSelection;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.jpa.ObfuscationJobRepository;
import pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out.jpa.entity.ObfuscationJobEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL-backed implementation of the obfuscation job journal. Selection, resolved
 * finding ids and outcomes are stored as JSONB via Jackson.
 */
@Component
@RequiredArgsConstructor
public class JpaObfuscationJobAdapter implements ObfuscationJobStore {

    private static final TypeReference<List<String>> FINDING_IDS_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, FindingRedactionOutcome>> OUTCOMES_TYPE = new TypeReference<>() {
    };

    private final ObfuscationJobRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * @throws ObfuscationJobAlreadyRunningException when the partial unique index on
     *                                               RUNNING jobs per space rejects the insert
     */
    @Override
    @Transactional
    public void create(ObfuscationJob job) {
        try {
            repository.saveAndFlush(toEntity(job));
        } catch (DataIntegrityViolationException e) {
            throw new ObfuscationJobAlreadyRunningException(job.spaceKey());
        }
    }

    @Override
    @Transactional
    public void update(ObfuscationJob job) {
        repository.save(toEntity(job));
    }

    @Override
    public Optional<ObfuscationJob> findById(String jobId) {
        return repository.findById(jobId).map(this::toDomain);
    }

    @Override
    public Optional<ObfuscationJob> findActiveBySpace(String spaceKey) {
        return repository.findFirstBySpaceKeyAndStatus(spaceKey, ObfuscationJobStatus.RUNNING.name())
                .map(this::toDomain);
    }

    @Override
    @Transactional
    public int markInterruptedOnBoot() {
        return repository.markRunningAsInterrupted(Instant.now());
    }

    private ObfuscationJobEntity toEntity(ObfuscationJob job) {
        return ObfuscationJobEntity.builder()
                .id(job.id())
                .spaceKey(job.spaceKey())
                .status(job.status().name())
                .submittedSelection(objectMapper.valueToTree(job.submittedSelection()))
                .resolvedFindingIds(objectMapper.valueToTree(job.resolvedFindingIds()))
                .processed(job.processed())
                .total(job.total())
                .outcomes(objectMapper.valueToTree(job.outcomes()))
                .actor(job.actor())
                .createdAt(job.createdAt())
                .updatedAt(job.updatedAt())
                .build();
    }

    private ObfuscationJob toDomain(ObfuscationJobEntity entity) {
        return ObfuscationJob.builder()
                .id(entity.getId())
                .spaceKey(entity.getSpaceKey())
                .status(ObfuscationJobStatus.valueOf(entity.getStatus()))
                .submittedSelection(objectMapper.convertValue(entity.getSubmittedSelection(), RemediationSelection.class))
                .resolvedFindingIds(objectMapper.convertValue(entity.getResolvedFindingIds(), FINDING_IDS_TYPE))
                .processed(entity.getProcessed())
                .total(entity.getTotal())
                .outcomes(objectMapper.convertValue(entity.getOutcomes(), OUTCOMES_TYPE))
                .actor(entity.getActor())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
