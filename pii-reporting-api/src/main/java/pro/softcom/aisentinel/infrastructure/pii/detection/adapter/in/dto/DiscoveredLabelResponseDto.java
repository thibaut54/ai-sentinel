package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto;

import pro.softcom.aisentinel.domain.pii.detection.DiscoveredLabel;

import java.time.LocalDateTime;

/**
 * Response DTO for a MINISTRAL discovered label.
 */
public record DiscoveredLabelResponseDto(
        String label,
        long occurrenceCount,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen,
        String status
) {
    public static DiscoveredLabelResponseDto fromDomain(DiscoveredLabel label) {
        return new DiscoveredLabelResponseDto(
                label.label(),
                label.occurrenceCount(),
                label.firstSeen(),
                label.lastSeen(),
                label.status().name()
        );
    }
}
