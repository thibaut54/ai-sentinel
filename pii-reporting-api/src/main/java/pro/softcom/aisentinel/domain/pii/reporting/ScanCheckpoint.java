package pro.softcom.aisentinel.domain.pii.reporting;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.export.SourceType;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Fine-grained checkpoint to resume scanning inside a source at content/attachment level.
 * Business goal: allow resuming a scan after interruption without reprocessing already analyzed items.
 */
@Builder
public record ScanCheckpoint(
    String scanId,
    SourceType sourceType,
    String sourceKey,
    String lastProcessedContentId,
    String lastProcessedAttachmentName,
    ScanStatus scanStatus,
    Double progressPercentage,
    LocalDateTime updatedAt
) {
    public ScanCheckpoint {
        Objects.requireNonNull(scanStatus, "scanStatus must not be null");
    }
}
