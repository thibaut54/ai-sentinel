package pro.softcom.aisentinel.application.sharepoint.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.sharepoint.port.in.StreamSharePointScanPort;
import pro.softcom.aisentinel.application.sharepoint.service.SharePointAccessor;
import pro.softcom.aisentinel.application.sharepoint.service.SharePointTextExtractorPort;
import pro.softcom.aisentinel.application.pii.reporting.port.out.PersonallyIdentifiableInformationScanExecutionOrchestratorPort;
import pro.softcom.aisentinel.application.pii.reporting.service.ContentScanOrchestrator;
import pro.softcom.aisentinel.application.pii.reporting.usecase.DetectionReportingEventType;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.ScanProgress;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointScannableFile;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Use case orchestrating SharePoint scans and PII detection.
 * Returns a reactive stream of scan events for SSE consumption.
 */
@RequiredArgsConstructor
@Slf4j
public class StreamSharePointScanUseCase implements StreamSharePointScanPort {

    private final SharePointAccessor sharePointAccessor;
    private final SharePointTextExtractorPort textExtractor;
    private final PiiDetectorClient piiDetectorClient;
    private final ContentScanOrchestrator contentScanOrchestrator;
    private final PersonallyIdentifiableInformationScanExecutionOrchestratorPort scanExecutionOrchestrator;

    @Override
    public Flux<ContentScanResult> scanAllSites() {
        String scanId = UUID.randomUUID().toString();
        log.info("[SHAREPOINT-SCAN] Creating new scan with scanId: {}", scanId);

        contentScanOrchestrator.purgePreviousScanData();

        Flux<ContentScanResult> header = buildHeader(scanId);
        Flux<ContentScanResult> body = buildAllSitesScanBody(scanId);
        Flux<ContentScanResult> footer = buildFooter(scanId);

        Flux<ContentScanResult> scanFlux = Flux.concat(header, body, footer);

        scanExecutionOrchestrator.startScan(scanId, scanFlux);
        return scanExecutionOrchestrator.subscribeScan(scanId);
    }

    @Override
    public Flux<ContentScanResult> scanSelectedSites(List<String> siteIds) {
        String scanId = UUID.randomUUID().toString();
        log.info("[SHAREPOINT-SCAN] Creating new selected sites scan with scanId: {}", scanId);

        contentScanOrchestrator.purgePreviousScanDataForSpaces(siteIds);

        Flux<ContentScanResult> header = buildHeader(scanId);
        Flux<ContentScanResult> body = buildSelectedSitesScanBody(scanId, siteIds);
        Flux<ContentScanResult> footer = buildFooter(scanId);

        Flux<ContentScanResult> scanFlux = Flux.concat(header, body, footer);

        scanExecutionOrchestrator.startScan(scanId, scanFlux);
        return scanExecutionOrchestrator.subscribeScan(scanId);
    }

    private Flux<ContentScanResult> buildAllSitesScanBody(String scanId) {
        return Mono.fromFuture(sharePointAccessor.getAllSites())
                .flatMapMany(sites -> {
                    Flux<ContentScanResult> errorFlux = createErrorIfNoSites(scanId, sites);
                    return Objects.requireNonNullElseGet(errorFlux, () -> createSiteScanFlux(scanId, sites));
                })
                .onErrorResume(exception -> {
                    log.error("[SHAREPOINT-SCAN] Error in scan flux: {}", exception.getMessage(), exception);
                    return errorEvent(scanId, null, exception.getMessage());
                });
    }

    private Flux<ContentScanResult> buildSelectedSitesScanBody(String scanId, List<String> siteIds) {
        return Mono.fromFuture(sharePointAccessor.getAllSites())
                .flatMapMany(allSites -> {
                    List<SharePointSite> selected = allSites.stream()
                            .filter(s -> siteIds.contains(s.id()))
                            .toList();

                    Flux<ContentScanResult> errorFlux = createErrorIfNoSites(scanId, selected);
                    return Objects.requireNonNullElseGet(errorFlux, () -> createSiteScanFlux(scanId, selected));
                })
                .onErrorResume(exception -> {
                    log.error("[SHAREPOINT-SCAN] Error in selected sites scan flux: {}", exception.getMessage(), exception);
                    return errorEvent(scanId, null, exception.getMessage());
                });
    }

    private Flux<ContentScanResult> createSiteScanFlux(String scanId, List<SharePointSite> sites) {
        return Flux.fromIterable(sites)
                .concatMap(site -> scanSite(scanId, site)
                        .onErrorResume(exception -> {
                            log.error("[SHAREPOINT-SCAN] Error during site scan {}: {}",
                                    site.id(), exception.getMessage(), exception);
                            return errorEvent(scanId, site.id(), exception.getMessage());
                        }));
    }

    private Flux<ContentScanResult> scanSite(String scanId, SharePointSite site) {
        return Mono.fromFuture(sharePointAccessor.getAllFilesInSite(site.id()))
                .flatMapMany(files -> {
                    int total = files.size();
                    AtomicInteger index = new AtomicInteger(0);

                    Flux<ContentScanResult> startEvent = Flux.just(
                            contentScanOrchestrator.createStartEvent(scanId, site.id(), total, 0.0));

                    Flux<ContentScanResult> fileEvents = Flux.fromIterable(files)
                            .publishOn(Schedulers.boundedElastic())
                            .concatMap(file -> {
                                int currentIndex = index.incrementAndGet();
                                ScanProgress progress = new ScanProgress(currentIndex, 0, total, total);
                                return processFile(scanId, site.id(), file, progress);
                            })
                            .onErrorContinue((exception, ignoredElement) -> log.error(
                                    "[SHAREPOINT-SCAN] Error processing file: {}", exception.getMessage(), exception));

                    Flux<ContentScanResult> completeEvent = Flux.just(
                            contentScanOrchestrator.createCompleteEvent(scanId, site.id()));

                    return Flux.concat(startEvent, fileEvents, completeEvent)
                            .doOnEach(signal -> {
                                if (signal.isOnNext() && signal.get() != null) {
                                    ContentScanResult event = signal.get();
                                    contentScanOrchestrator.persistCheckpointSynchronously(event);
                                    Mono.fromRunnable(() -> contentScanOrchestrator.persistEventAsyncOperations(event, "SHAREPOINT"))
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .retryWhen(Retry.backoff(3, Duration.ofMillis(100)))
                                            .onErrorResume(e -> {
                                                log.warn("[SHAREPOINT-SCAN] Failed to persist async operations: {}", e.getMessage());
                                                return Mono.empty();
                                            })
                                            .subscribe();
                                }
                            });
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
                    log.error("[SHAREPOINT-SCAN] Error analyzing file {}: {}", file.name(), exception.getMessage());
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

    private static Flux<ContentScanResult> buildHeader(String scanId) {
        return Flux.just(ContentScanResult.builder()
                .scanId(scanId)
                .eventType(DetectionReportingEventType.MULTI_START.getLabel())
                .emittedAt(Instant.now().toString())
                .build());
    }

    private static Flux<ContentScanResult> buildFooter(String scanId) {
        return Flux.just(ContentScanResult.builder()
                .scanId(scanId)
                .eventType(DetectionReportingEventType.MULTI_COMPLETE.getLabel())
                .emittedAt(Instant.now().toString())
                .build());
    }

    private static Flux<ContentScanResult> createErrorIfNoSites(String scanId, List<SharePointSite> sites) {
        if (sites == null || sites.isEmpty()) {
            return errorEvent(scanId, null, "No SharePoint site found");
        }
        return null;
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
