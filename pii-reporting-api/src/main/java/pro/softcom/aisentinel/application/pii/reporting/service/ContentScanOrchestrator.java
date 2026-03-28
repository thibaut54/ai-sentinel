package pro.softcom.aisentinel.application.pii.reporting.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.pii.reporting.ScanSeverityCountService;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanEventStore;
import pro.softcom.aisentinel.domain.confluence.AttachmentInfo;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;
import pro.softcom.aisentinel.domain.pii.scan.ScanEventType;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;

/**
 * Orchestrates scan event lifecycle: creation, progress tracking, and persistence.
 * Business purpose: Coordinates the generation of scan events and manages scan checkpoints
 * to enable resumable scans and progress reporting.
 * Additionally calculates and persists severity counts for PII detections.
 */
@RequiredArgsConstructor
@Slf4j
public class ContentScanOrchestrator {

    private final ScanEventFactory scanEventFactory;
    private final ScanProgressCalculator scanProgressCalculator;
    private final ScanCheckpointService scanCheckpointService;
    private final ScanEventStore scanEventStore;
    private final ScanEventDispatcher scanEventDispatcher;
    private final SeverityCalculationService severityCalculationService;
    private final ScanSeverityCountService scanSeverityCountService;

    public ContentScanResult createStartEvent(String scanId, String sourceId, int total, double progress) {
        return scanEventFactory.createStartEvent(scanId, sourceId, total, progress);
    }

    public ContentScanResult createCompleteEvent(String scanId, String sourceId) {
        return scanEventFactory.createCompleteEvent(scanId, sourceId);
    }

    public ContentScanResult createContentStartEvent(String scanId, String sourceId, ScannableContent content,
                                                     int currentIndex, int total, double progress) {
        return scanEventFactory.createContentStartEvent(scanId, sourceId, content, currentIndex, total, progress);
    }

    public ContentScanResult createContentCompleteEvent(String scanId, String sourceId, ScannableContent content,
                                                        double progress) {
        return scanEventFactory.createContentCompleteEvent(scanId, sourceId, content, progress);
    }

    public ContentScanResult createContentItemEvent(String scanId, String sourceId, ScannableContent content,
                                                    String sourceContent, ContentPiiDetection detection, double progress) {
        return scanEventFactory.createContentItemEvent(scanId, sourceId, content, sourceContent, detection, progress);
    }

    public ContentScanResult createEmptyContentItemEvent(String scanId, String sourceId, ScannableContent content,
                                                         double progress) {
        return scanEventFactory.createEmptyContentItemEvent(scanId, sourceId, content, progress);
    }

    public ContentScanResult createAttachmentItemEvent(String scanId, String sourceId, ScannableContent content,
                                                       AttachmentInfo attachment,
                                                       String sourceContent, ContentPiiDetection detection,
                                                       double progress) {
        return scanEventFactory.createAttachmentItemEvent(scanId, sourceId, content, attachment, sourceContent,
                detection, progress);
    }

    public ContentScanResult createErrorEvent(String scanId, String sourceId, String contentId,
                                              String message, double progress) {
        return scanEventFactory.createErrorEvent(scanId, sourceId, contentId, message, progress);
    }

    public double calculateProgress(int analyzed, int total) {
        return scanProgressCalculator.calculateProgress(analyzed, total);
    }

    /**
     * Persists checkpoint synchronously to ensure consistent state for scan resume.
     *
     * <p>Business purpose: The checkpoint MUST be persisted synchronously to avoid race conditions
     * when the user refreshes the page during an active scan. If checkpoint persistence were async,
     * a refresh could read stale checkpoint data and re-scan pages that were already processed,
     * leading to duplicated severity counts.
     *
     * @param event      the scan event containing checkpoint data
     * @param sourceType the type of the datasource being scanned
     */
    public void persistCheckpointSynchronously(ContentScanResult event, SourceType sourceType) {
        try {
            scanCheckpointService.persistCheckpoint(event, sourceType);
        } catch (Exception e) {
            log.warn("[CHECKPOINT] Failed to persist checkpoint synchronously: {}", e.getMessage());
        }
    }

    /**
     * Persists severity counts, event store, and dispatches notifications with source type.
     *
     * <p>Business purpose: These operations can be executed asynchronously as they are
     * additive (severity counts increment) or non-critical for scan resume (event store).
     * This allows the SSE stream to remain responsive while background persistence occurs.
     *
     * @param event      the scan event to process
     * @param sourceType the source type discriminator
     */
    public void persistEventAsyncOperations(ContentScanResult event, SourceType sourceType) {
        // Calculate and persist severity counts if event contains PII detections
        if (event.detectedPIIList() != null && !event.detectedPIIList().isEmpty()) {
            SeverityCounts counts = severityCalculationService.aggregateCounts(event.detectedPIIList());
            scanSeverityCountService.incrementCounts(event.scanId(), sourceType, event.sourceId(), counts);
        }

        if (scanEventStore != null) {
            scanEventStore.append(event, sourceType);

            // Has findings?
            if (shouldPublishEvent(event) && sourceType != null) {
                // Publish the event only if transaction successfully committed
                scanEventDispatcher.publishAfterCommit(event.scanId(), event.sourceId(), sourceType);
            }
        }
    }

    private static boolean shouldPublishEvent(ContentScanResult event) {
        return ScanEventType.COMPLETE.getValue().equals(event.eventType());
    }

    /**
     * Purges previous scan data for a given source type to ensure a clean state before starting a new scan.
     *
     * @param sourceType the type of the datasource to purge
     */
    public void purgePreviousScanData(SourceType sourceType) {
        try {
            log.info("[SCAN] Purging previous active scan data for source type {} before starting new scan", sourceType);
            scanCheckpointService.deleteActiveScanCheckpoints(sourceType);
            log.info("[SCAN] Previous scan data purged successfully for source type {}", sourceType);
        } catch (Exception e) {
            log.error("[SCAN] Failed to purge previous scan data for source type {}: {}", sourceType, e.getMessage(), e);
            // Don't fail the scan if purge fails - log and continue
        }
    }

    /**
     * Purges previous scan data for selected sources to ensure a clean state before starting a new scan.
     *
     * @param sourceType the type of the datasource
     * @param sourceKeys list of source keys to purge
     */
    public void purgePreviousScanDataForSources(SourceType sourceType, java.util.List<String> sourceKeys) {
        try {
            log.info("[SCAN] Purging ALL previous scan data for selected sources before starting new scan");
            scanCheckpointService.deleteAllCheckpointsForSources(sourceType, sourceKeys);
            log.info("[SCAN] Previous scan data for selected sources purged successfully");
        } catch (Exception e) {
            log.error("[SCAN] Failed to purge previous scan data for selected sources: {}", e.getMessage(), e);
            // Don't fail the scan if purge fails - log and continue
        }
    }
}
