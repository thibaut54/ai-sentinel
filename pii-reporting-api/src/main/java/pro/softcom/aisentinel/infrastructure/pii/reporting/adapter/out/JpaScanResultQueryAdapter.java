package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;
import pro.softcom.aisentinel.application.pii.security.PiiAccessAuditService;
import pro.softcom.aisentinel.application.pii.security.ScanResultEncryptor;
import pro.softcom.aisentinel.domain.pii.reporting.AccessPurpose;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.LastScanMeta;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.DetectionEventRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanEventEntity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Adapter JPA implementant le port de lecture ScanResultQuery.
 * Mappe les entites/projections JPA vers des modeles du domaine.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JpaScanResultQueryAdapter implements ScanResultQuery {

    public static final String SCAN_EVENT_ENTITY_NULL_ERROR_MESSAGE = "ScanEventEntity or payload is null";
    private final DetectionEventRepository eventRepository;
    private final ScanResultEncryptor scanResultEncryptor;
    private final PiiAccessAuditService auditService;
    private final ObjectMapper objectMapper;

    private static final Set<String> ITEM_EVENT_TYPES = Set.of("item", "attachmentItem");

    @Override
    public Optional<LastScanMeta> findLatestScan() {
        var rows = eventRepository.findLatestScanGrouped(PageRequest.of(0, 1));
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        var row = rows.getFirst();
        String scanId = row.getScanId();
        int sources = eventRepository.countDistinctSourceKeyByScanId(scanId);
        return Optional.of(new LastScanMeta(scanId, row.getLastUpdated(), sources));
    }

    @Override
    public List<SpaceCounter> getSpaceCounters(String scanId) {
        if (scanId == null || scanId.isBlank()) {
            return List.of();
        }
        return eventRepository.aggregateSpaceCounters(scanId).stream()
            .map(projection -> new SpaceCounter(projection.getSourceKey(), projection.getPagesDone(), projection.getAttachmentsDone(), projection.getLastEventTs()))
            .toList();
    }

    @Override
    public List<ContentScanResult> listItemEvents(String scanId) {
        if (scanId == null || scanId.isBlank()) return List.of();
        var types = Set.of("item", "attachmentItem");
        return eventRepository.findByScanIdAndEventTypeInOrderByEventSeqAsc(scanId, types).stream()
            .map(this::toDomain)
            .filter(Objects::nonNull)
            .toList();
    }

    private ContentScanResult toDomain(ScanEventEntity scanEventEntity) {
        if (scanEventEntity == null || scanEventEntity.getPayload() == null) {
            log.warn(SCAN_EVENT_ENTITY_NULL_ERROR_MESSAGE);
            return null;
        }

        try {
            ContentScanResult encryptedResult = objectMapper.treeToValue(scanEventEntity.getPayload(), ContentScanResult.class);
            return scanResultEncryptor.decrypt(encryptedResult);
        } catch (Exception e) {
            log.error("Failed to deserialize scan event", e);
            return null;
        }
    }
    @Override
    public List<ContentScanResult> listItemEventsEncrypted(String scanId) {
        if (scanId == null || scanId.isBlank()) {
            return List.of();
        }

        return eventRepository.findByScanIdAndEventTypeInOrderByEventSeqAsc(scanId, ITEM_EVENT_TYPES).stream()
            .map(this::toEncryptedDomain)
            .filter(Objects::nonNull)
            .toList();
    }

    @Override
    public List<ContentScanResult> listItemEventsEncryptedBySourceKey(String scanId, String sourceKey) {
        if (scanId == null || scanId.isBlank() || sourceKey == null || sourceKey.isBlank()) {
            return List.of();
        }
        return eventRepository.findByScanIdAndSourceKeyAndEventTypeInOrderByEventSeqAsc(scanId, sourceKey, ITEM_EVENT_TYPES).stream()
                .map(this::toEncryptedDomain)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<ContentScanResult> listItemEventsDecrypted(String scanId, String contentId, AccessPurpose purpose) {
        if (scanId == null || scanId.isBlank()) {
            return List.of();
        }

        List<ContentScanResult> results = eventRepository
            .findByScanIdAndContentIdAndEventTypeInOrderByEventSeqAsc(scanId, contentId, ITEM_EVENT_TYPES).stream()
            .map(this::toDecryptedDomain)
            .filter(Objects::nonNull)
            .toList();

        if (results.isEmpty()) {
            return results;
        }

        // Audit access for GDPR/nLPD compliance
        int totalPiiCount = results.stream()
            .mapToInt(r -> r.detectedPIIList() != null ? r.detectedPIIList().size() : 0)
            .sum();

        var sourceKey = results.getFirst().sourceId();
        var contentTitle = results.getFirst().contentTitle();
        auditService.auditPiiAccess(scanId, sourceKey, contentId, contentTitle, purpose, totalPiiCount);

        return results;
    }

    private ContentScanResult toEncryptedDomain(ScanEventEntity entity) {
        if (entity == null || entity.getPayload() == null) {
            log.warn(SCAN_EVENT_ENTITY_NULL_ERROR_MESSAGE);
            return null;
        }

        try {
            // Return as-is (already encrypted in DB)
            return objectMapper.treeToValue(entity.getPayload(), ContentScanResult.class);
        } catch (Exception e) {
            log.error("Failed to deserialize scan event", e);
            return null;
        }
    }

    private ContentScanResult toDecryptedDomain(ScanEventEntity entity) {
        if (entity == null || entity.getPayload() == null) {
            log.warn(SCAN_EVENT_ENTITY_NULL_ERROR_MESSAGE);
            return null;
        }

        try {
            ContentScanResult encrypted = objectMapper.treeToValue(entity.getPayload(), ContentScanResult.class);
            return scanResultEncryptor.decrypt(encrypted);
        } catch (Exception e) {
            log.error("Failed to decrypt scan event", e);
            return null;
        }
    }
}
