package pro.softcom.aisentinel.domain.pii.detection;

import java.time.LocalDateTime;

/**
 * An open-vocabulary label emitted by the MINISTRAL detector that has no
 * {@code pii_type_config} row yet.
 * <p>
 * Business purpose: pure domain read model backing the discovery inbox, letting
 * an operator review the model's proposals and promote or ignore them. It only
 * carries the UPPER_SNAKE label, aggregated statistics and its lifecycle status,
 * never any PII value.
 *
 * @param label           the UPPER_SNAKE label proposed by the detector
 * @param occurrenceCount total occurrences accumulated across scans
 * @param firstSeen       timestamp of the first occurrence
 * @param lastSeen        timestamp of the most recent occurrence
 * @param status          current lifecycle status
 */
public record DiscoveredLabel(
    String label,
    long occurrenceCount,
    LocalDateTime firstSeen,
    LocalDateTime lastSeen,
    DiscoveredLabelStatus status
) {
}
