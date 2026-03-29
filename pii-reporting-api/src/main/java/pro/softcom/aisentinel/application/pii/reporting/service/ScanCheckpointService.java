package pro.softcom.aisentinel.application.pii.reporting.service;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.ScanCheckpoint;
import pro.softcom.aisentinel.domain.pii.scan.Initiator;
import pro.softcom.aisentinel.domain.pii.scan.ScanCheckpointStatusTransition;

/**
 * Manages scan checkpoint persistence for resume capability.
 * Business intent: Tracks scan progress to enable resuming interrupted scans.
 */
@RequiredArgsConstructor
@Slf4j
public class ScanCheckpointService {

    private final ScanCheckpointRepository scanCheckpointRepository;

    /**
     * Persists checkpoint based on scan event and source type.
     * Protected against thread interruptions to ensure checkpoint persistence
     * even when SSE client disconnects.
     *
     * @param result     the scan event to persist
     * @param sourceType the type of the datasource being scanned
     */
    public void persistCheckpoint(ContentScanResult result, SourceType sourceType) {
        if (!isValidForCheckpoint(result)) {
            return;
        }

        boolean wasInterrupted = false;
        try {
            // Clear interruption flag to allow DB operation to proceed
            if (Thread.interrupted()) {
                wasInterrupted = true;
                log.debug("[CHECKPOINT] Thread interrupted, clearing flag to persist checkpoint");
            }

            ScanCheckpoint checkpoint = buildCheckpoint(result, sourceType);
            if (checkpoint != null) {
                scanCheckpointRepository.save(checkpoint);
                log.debug("[CHECKPOINT] Saved checkpoint for scan {}", result.scanId());
            }
        } catch (OptimisticLockException _) {
            // Concurrent modification detected - another thread updated this checkpoint first
            // This is expected behavior with optimistic locking, not an error
            log.info("[CHECKPOINT] Concurrent update detected for scan {} source {}, skipping (another process already updated)",
                result.scanId(), result.sourceId());
        } catch (Exception exception) {
            // Check if this is a DB error caused by thread interruption (normal on SSE disconnect)
            if (isInterruptionCausedError(exception)) {
                log.info("[CHECKPOINT] Checkpoint persistence interrupted (SSE disconnection): {}",
                         exception.getMessage());
            } else {
                log.warn("[CHECKPOINT] Unable to persist checkpoint: {}", exception.getMessage());
            }
        } finally {
            // Restore interruption flag if it was set
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
                log.debug("[CHECKPOINT] Restored thread interrupt flag after checkpoint persistence");
            }
        }
    }

    private boolean isValidForCheckpoint(ContentScanResult result) {
        if (result == null) {
            return false;
        }
        String scanId = result.scanId();
        String sourceId = result.sourceId();
        return !StringUtils.isBlank(scanId) && !StringUtils.isBlank(sourceId);
    }

    private ScanCheckpoint buildCheckpoint(ContentScanResult result, SourceType sourceType) {
        String eventType = result.eventType();
        if (eventType == null) {
            return null;
        }

        CheckpointData data = extractCheckpointData(eventType, result);
        ScanCheckpoint existingCheckpoint = scanCheckpointRepository
            .findByScanAndSource(result.scanId(), sourceType, result.sourceId())
            .orElse(null);

        if (existingCheckpoint != null) {
            ScanCheckpointStatusTransition transition = new ScanCheckpointStatusTransition(
                existingCheckpoint.scanStatus(),
                Initiator.SYSTEM
            );

            boolean isTransitionForbidden = data != null && !transition.isTransitionAllowed(data.status());
            if (isTransitionForbidden) {
                log.warn("[CHECKPOINT] Transition {} -> {} not allowed, skipping",
                         existingCheckpoint.scanStatus(), data.status());
                return null;
            }
        }

        if (data == null) {
            return null;
        }

        return ScanCheckpoint.builder()
            .scanId(result.scanId())
            .sourceType(sourceType)
            .sourceKey(result.sourceId())
            .lastProcessedContentId(data.lastPage())
            .lastProcessedAttachmentName(data.lastAttachment())
            .scanStatus(data.status())
            .progressPercentage(result.analysisProgressPercentage())
            .build();
    }

    private CheckpointData extractCheckpointData(String eventType, ContentScanResult result) {
        return switch (eventType) {
            case "item" ->
                // Interim page item: persist checkpoint with RUNNING status
                // Pass null for lastProcessedContentId - repository merge strategy preserves existing value
                new CheckpointData(null, null, ScanStatus.RUNNING);
            case "attachmentItem" ->
                // Persist attachment progress but do NOT advance lastProcessedContentId
                // Repository merge strategy preserves existing lastProcessedContentId
                new CheckpointData(null, result.attachmentName(), ScanStatus.RUNNING);
            case "pageComplete" ->
                // Persist progress at end of page - advance lastProcessedContentId
                new CheckpointData(result.contentId(), null, ScanStatus.RUNNING);
            case "complete" ->
                // Source-level completion - reset lastProcessedContentId
                new CheckpointData(null, null, ScanStatus.COMPLETED);
            default -> null; // Ignore other events
        };
    }

    /**
     * Checks if an exception was caused by thread interruption.
     * This is normal when SSE client disconnects during a scan.
     *
     * @param exception The exception to check
     * @return true if the exception was caused by thread interruption
     */
    private boolean isInterruptionCausedError(Exception exception) {
        // Check exception message for interrupt-related keywords
        if (exception.getMessage() != null &&
            exception.getMessage().toLowerCase().contains("interrupt")) {
            return true;
        }

        // Walk through the cause chain looking for interruption-related exceptions
        Throwable cause = exception.getCause();
        while (cause != null) {
            // Check for SocketException with interrupt message (PostgreSQL connection interrupted)
            if (cause instanceof java.net.SocketException &&
                cause.getMessage() != null &&
                cause.getMessage().toLowerCase().contains("interrupt")) {
                return true;
            }

            // Check for direct InterruptedException
            if (cause instanceof InterruptedException) {
                return true;
            }

            // Avoid infinite loops in circular cause chains
            if (cause.getCause() == cause) {
                break;
            }

            cause = cause.getCause();
        }

        return false;
    }

    /**
     * Internal record for checkpoint data extraction.
     */
    private record CheckpointData(String lastPage, String lastAttachment, ScanStatus status) {
    }

    /**
     * Deletes all active scan checkpoints for a given source type.
     *
     * @param sourceType the type of the datasource to clean up
     */
    public void deleteActiveScanCheckpoints(SourceType sourceType) {
        scanCheckpointRepository.deleteActiveScanCheckpointsBySourceType(sourceType);
    }

    /**
     * Deletes ALL scan checkpoints for specific sources of a given type, regardless of status.
     * Business purpose: When re-scanning selected sources, all previous checkpoint data
     * (including COMPLETED) must be removed so the dashboard summary does not return
     * stale statuses before the new scan creates its own checkpoints.
     *
     * @param sourceType the type of the datasource
     * @param sourceKeys list of source keys to purge
     */
    public void deleteAllCheckpointsForSources(SourceType sourceType, java.util.List<String> sourceKeys) {
        scanCheckpointRepository.deleteAllCheckpointsForSources(sourceType, sourceKeys);
    }

    public void deleteAllCheckpointsBySourceType(SourceType sourceType) {
        scanCheckpointRepository.deleteAllBySourceType(sourceType);
    }

    public void deleteAllCheckpointsBySourceTypeAndSourceKeys(SourceType sourceType, java.util.List<String> sourceKeys) {
        scanCheckpointRepository.deleteAllBySourceTypeAndSourceKeys(sourceType, sourceKeys);
    }
}
