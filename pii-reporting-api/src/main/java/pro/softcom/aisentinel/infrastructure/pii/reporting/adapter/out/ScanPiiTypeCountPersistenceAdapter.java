package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanPiiTypeCountRepository;
import pro.softcom.aisentinel.domain.pii.reporting.ScanPiiTypeCount;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.ScanPiiTypeCountJpaRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanPiiTypeCountEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA adapter implementing {@link ScanPiiTypeCountRepository} port.
 *
 * <p>Bridges the hexagonal architecture boundary between application layer
 * (domain-focused use cases) and infrastructure layer (JPA persistence).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Maps domain objects (ScanPiiTypeCount) to/from JPA entities</li>
 *   <li>Delegates persistence operations to JPA repository</li>
 *   <li>Provides atomic increment operations via PostgreSQL UPSERT</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ScanPiiTypeCountPersistenceAdapter implements ScanPiiTypeCountRepository {

    private final ScanPiiTypeCountJpaRepository jpaRepository;

    @Override
    public void incrementCounts(String scanId, String spaceKey, Map<String, Integer> delta) {
        if (delta == null) {
            return;
        }
        for (Map.Entry<String, Integer> entry : delta.entrySet()) {
            Integer count = entry.getValue();
            if (entry.getKey() == null || count == null || count <= 0) {
                continue;
            }
            jpaRepository.incrementCounts(scanId, spaceKey, entry.getKey(), count);
        }
    }

    @Override
    public Map<String, Integer> findCountsByScanIdAndSpaceKey(String scanId, String spaceKey) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ScanPiiTypeCountEntity entity : jpaRepository.findById_ScanIdAndId_SpaceKey(scanId, spaceKey)) {
            counts.put(entity.getPiiType(), entity.getOccurrenceCount());
        }
        return counts;
    }

    @Override
    public List<ScanPiiTypeCount> findByScanId(String scanId) {
        Map<String, Map<String, Integer>> countsBySpace = new LinkedHashMap<>();
        for (ScanPiiTypeCountEntity entity : jpaRepository.findById_ScanIdOrderById_SpaceKey(scanId)) {
            countsBySpace
                .computeIfAbsent(entity.getSpaceKey(), key -> new LinkedHashMap<>())
                .put(entity.getPiiType(), entity.getOccurrenceCount());
        }
        return countsBySpace.entrySet().stream()
            .map(entry -> new ScanPiiTypeCount(scanId, entry.getKey(), entry.getValue()))
            .toList();
    }

    @Override
    public void deleteByScanId(String scanId) {
        jpaRepository.deleteById_ScanId(scanId);
    }
}
