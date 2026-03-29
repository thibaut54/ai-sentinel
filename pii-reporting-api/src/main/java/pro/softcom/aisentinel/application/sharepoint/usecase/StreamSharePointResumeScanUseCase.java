package pro.softcom.aisentinel.application.sharepoint.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.pii.reporting.service.ContentScanOrchestrator;
import pro.softcom.aisentinel.application.pii.reporting.usecase.DetectionReportingEventType;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.application.sharepoint.port.in.StreamSharePointResumeScanPort;
import pro.softcom.aisentinel.application.sharepoint.service.SharePointAccessor;
import pro.softcom.aisentinel.application.sharepoint.service.SharePointTextExtractorPort;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.ScanCheckpoint;
import pro.softcom.aisentinel.domain.pii.scan.ScanProgress;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointScannableFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Use case for resuming a paused SharePoint scan from the last checkpoint.
 * Reads checkpoints per site, skips COMPLETED sites, and resumes
 * scanning from the last processed file.
 */
@RequiredArgsConstructor
@Slf4j
public class StreamSharePointResumeScanUseCase implements StreamSharePointResumeScanPort {

    private final SharePointAccessor sharePointAccessor;
    private final SharePointTextExtractorPort textExtractor;
    private final PiiDetectorClient piiDetectorClient;
    private final ContentScanOrchestrator contentScanOrchestrator;
    private final ScanCheckpointRepository scanCheckpointRepository;

    @Override
    public Flux<ContentScanResult> resumeAllSites(String scanId) {
        if (scanId == null || scanId.isBlank()) {
            return Flux.empty();
        }

        return Mono.fromFuture(sharePointAccessor.getAllSites())
            .flatMapMany(sites ->
                Flux.fromIterable(sites)
                    .concatMap(site -> resumeSite(scanId, site.id())))
            .onErrorResume(exception -> {
                log.error("[SHAREPOINT-RESUME] Error when resuming scan: {}", exception.getMessage(), exception);
                return errorEvent(scanId, null, exception.getMessage());
            });
    }

    private Flux<ContentScanResult> resumeSite(String scanId, String siteId) {
        try {
            var checkpoint = scanCheckpointRepository
                .findByScanAndSource(scanId, SourceType.SHAREPOINT, siteId)
                .orElse(null);

            if (checkpoint != null && checkpoint.scanStatus() == ScanStatus.COMPLETED) {
                return Flux.empty();
            }

            return Mono.fromFuture(sharePointAccessor.getAllFilesInSite(siteId))
                .flatMapMany(files -> {
                    var remaining = computeRemainingFiles(files, checkpoint);
                    if (remaining.isEmpty()) {
                        return Flux.empty();
                    }
                    int originalTotal = files.size();
                    int analyzedOffset = originalTotal - remaining.size();
                    return runResumeScanFlux(scanId, siteId, remaining, analyzedOffset, originalTotal);
                })
                .onErrorResume(exception -> {
                    log.error("[SHAREPOINT-RESUME] Error resuming site {}: {}",
                        siteId, exception.getMessage(), exception);
                    return errorEvent(scanId, siteId, exception.getMessage());
                });
        } catch (Exception exception) {
            log.error("[SHAREPOINT-RESUME] Error resuming site {}: {}",
                siteId, exception.getMessage(), exception);
            return errorEvent(scanId, siteId, exception.getMessage());
        }
    }

    private Flux<ContentScanResult> runResumeScanFlux(String scanId, String siteId,
                                                       List<SharePointDriveItem> files,
                                                       int analyzedOffset, int originalTotal) {
        int total = files.size();
        AtomicInteger index = new AtomicInteger(0);

        Flux<ContentScanResult> startEvent = Flux.just(
            contentScanOrchestrator.createStartEvent(scanId, siteId, total,
                contentScanOrchestrator.calculateProgress(analyzedOffset, originalTotal)));

        Flux<ContentScanResult> fileEvents = Flux.fromIterable(files)
            .publishOn(Schedulers.boundedElastic())
            .concatMap(file -> {
                int currentIndex = index.incrementAndGet();
                ScanProgress progress = new ScanProgress(currentIndex, analyzedOffset, originalTotal, total);
                return processFile(scanId, siteId, file, progress);
            })
            .onErrorContinue((exception, ignoredElement) -> log.error(
                "[SHAREPOINT-RESUME] Error processing file: {}", exception.getMessage(), exception));

        Flux<ContentScanResult> completeEvent = Flux.just(
            contentScanOrchestrator.createCompleteEvent(scanId, siteId));

        return Flux.concat(startEvent, fileEvents, completeEvent)
            .doOnEach(signal -> {
                if (signal.isOnNext() && signal.get() != null) {
                    ContentScanResult event = signal.get();
                    contentScanOrchestrator.persistCheckpointSynchronously(event, SourceType.SHAREPOINT);
                    Mono.fromRunnable(() -> contentScanOrchestrator.persistEventAsyncOperations(event, SourceType.SHAREPOINT))
                        .subscribeOn(Schedulers.boundedElastic())
                        .retryWhen(Retry.backoff(3, Duration.ofMillis(100)))
                        .onErrorResume(e -> {
                            log.warn("[SHAREPOINT-RESUME] Failed to persist async operations: {}", e.getMessage());
                            return Mono.empty();
                        })
                        .subscribe();
                }
            });
    }

    private Flux<ContentScanResult> processFile(String scanId, String siteId,
                                                  SharePointDriveItem file, ScanProgress scanProgress) {
        SharePointScannableFile scannableFile = new SharePointScannableFile(siteId, file, null);

        double startProgress = contentScanOrchestrator.calculateProgress(
            scanProgress.analyzedOffset() + (scanProgress.currentIndex() - 1),
            scanProgress.originalTotal());

        ContentScanResult fileStart = contentScanOrchestrator.createContentStartEvent(
            scanId, siteId, scannableFile, scanProgress.currentIndex(), scanProgress.originalTotal(), startProgress);

        Flux<ContentScanResult> itemEvent = extractAndAnalyze(scanId, siteId, file, scanProgress);

        double completeProgress = contentScanOrchestrator.calculateProgress(
            scanProgress.analyzedOffset() + scanProgress.currentIndex(),
            scanProgress.originalTotal());
        ContentScanResult fileComplete = contentScanOrchestrator.createContentCompleteEvent(
            scanId, siteId, scannableFile, completeProgress);

        return Flux.just(fileStart)
            .concatWith(itemEvent)
            .concatWith(Mono.just(fileComplete))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ContentScanResult> extractAndAnalyze(String scanId, String siteId,
                                                       SharePointDriveItem file, ScanProgress scanProgress) {
        return Mono.fromFuture(sharePointAccessor.downloadContent(file.driveId(), file.id()))
            .flatMap(inputStream -> {
                if (inputStream == null) {
                    return Mono.just("");
                }
                return Mono.fromCallable(() -> textExtractor.extractText(inputStream, file.name(), file.mimeType()));
            })
            .flatMap(extractedText -> {
                SharePointScannableFile scannableFile = new SharePointScannableFile(siteId, file, extractedText);
                String content = scannableFile.getContentBody();

                if (content == null || content.isBlank()) {
                    double progress = contentScanOrchestrator.calculateProgress(
                        scanProgress.analyzedOffset() + scanProgress.currentIndex(),
                        scanProgress.originalTotal());
                    return Mono.just(contentScanOrchestrator.createEmptyContentItemEvent(
                        scanId, siteId, scannableFile, progress));
                }

                return Mono.fromCallable(() -> piiDetectorClient.analyzeContent(content))
                    .map(detection -> {
                        double progress = contentScanOrchestrator.calculateProgress(
                            scanProgress.analyzedOffset() + scanProgress.currentIndex(),
                            scanProgress.originalTotal());
                        return contentScanOrchestrator.createContentItemEvent(
                            scanId, siteId, scannableFile, content, detection, progress);
                    });
            })
            .onErrorResume(exception -> {
                log.error("[SHAREPOINT-RESUME] Error analyzing file {}: {}", file.name(), exception.getMessage());
                SharePointScannableFile scannableFile = new SharePointScannableFile(siteId, file, null);
                double progress = contentScanOrchestrator.calculateProgress(
                    scanProgress.analyzedOffset() + scanProgress.currentIndex(),
                    scanProgress.originalTotal());
                return Mono.just(contentScanOrchestrator.createErrorEvent(
                    scanId, siteId, scannableFile.getId(),
                    "Error analyzing file: " + exception.getMessage(), progress));
            })
            .flux();
    }

    private static List<SharePointDriveItem> computeRemainingFiles(List<SharePointDriveItem> files,
                                                                     ScanCheckpoint checkpoint) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        if (checkpoint == null) {
            return files;
        }
        if (checkpoint.scanStatus() == ScanStatus.COMPLETED) {
            return List.of();
        }

        String lastProcessedId = checkpoint.lastProcessedContentId();
        if (lastProcessedId == null || lastProcessedId.isBlank()) {
            return files;
        }

        int lastIndex = -1;
        for (int i = 0; i < files.size(); i++) {
            if (lastProcessedId.equals(files.get(i).id())) {
                lastIndex = i;
                break;
            }
        }

        if (lastIndex < 0) {
            return files;
        }

        int start = lastIndex + 1;
        if (start >= files.size()) {
            return List.of();
        }
        return files.subList(start, files.size());
    }

    private static Flux<ContentScanResult> errorEvent(String scanId, String sourceId, String message) {
        return Flux.just(ContentScanResult.builder()
            .scanId(scanId)
            .sourceId(sourceId)
            .eventType(DetectionReportingEventType.ERROR.getLabel())
            .message(message)
            .emittedAt(Instant.now().toString())
            .build());
    }
}
