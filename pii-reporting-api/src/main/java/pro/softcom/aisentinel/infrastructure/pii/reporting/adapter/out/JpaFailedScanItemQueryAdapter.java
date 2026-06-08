package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.reporting.port.out.FailedScanItemQuery;
import pro.softcom.aisentinel.application.pii.reporting.usecase.DetectionReportingEventType;
import pro.softcom.aisentinel.domain.pii.reporting.FailedScanItem;
import pro.softcom.aisentinel.domain.pii.reporting.FailedScanItem.ItemType;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.DetectionEventRepository;
import pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.jpa.entity.ScanEventEntity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JPA adapter reading failed scan items from the recorded error events.
 *
 * <p>Maps each error event to a page or attachment failure, deduplicates them
 * preserving first-seen order, and bounds the result for display.
 */
@Component
@RequiredArgsConstructor
public class JpaFailedScanItemQueryAdapter implements FailedScanItemQuery {

    private final DetectionEventRepository eventRepository;

    @Override
    public List<FailedScanItem> findFailedItems(String scanId, String spaceKey, int limit) {
        if (scanId == null || scanId.isBlank() || spaceKey == null || spaceKey.isBlank() || limit <= 0) {
            return List.of();
        }
        List<ScanEventEntity> errors = eventRepository.findByScanIdAndSpaceKeyAndEventTypeOrderByEventSeqAsc(
            scanId, spaceKey, DetectionReportingEventType.ERROR.getLabel());

        Set<FailedScanItem> deduplicated = new LinkedHashSet<>();
        for (ScanEventEntity error : errors) {
            FailedScanItem item = toFailedItem(error);
            if (item != null) {
                deduplicated.add(item);
            }
            if (deduplicated.size() >= limit) {
                break;
            }
        }
        return List.copyOf(deduplicated);
    }

    private FailedScanItem toFailedItem(ScanEventEntity error) {
        if (error.getAttachmentName() != null) {
            return new FailedScanItem(ItemType.ATTACHMENT, error.getAttachmentName());
        }
        String title = error.getPageTitle() != null ? error.getPageTitle() : error.getPageId();
        if (title == null) {
            return null;
        }
        return new FailedScanItem(ItemType.PAGE, title);
    }
}
