package pro.softcom.aisentinel.application.pii.reporting.usecase;

import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.confluence.service.ConfluenceAccessor;
import pro.softcom.aisentinel.application.pii.reporting.port.in.StreamConfluenceResumeScanPort;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanTimeOutConfig;
import pro.softcom.aisentinel.application.pii.reporting.service.AttachmentProcessor;
import pro.softcom.aisentinel.application.pii.reporting.service.ContentScanOrchestrator;
import pro.softcom.aisentinel.application.pii.reporting.service.parser.HtmlContentParser;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.domain.confluence.ConfluenceSpace;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.ScanCheckpoint;
import pro.softcom.aisentinel.domain.pii.reporting.ScanRemainingPages;
import pro.softcom.aisentinel.domain.pii.reporting.ScanRemainingPagesCalculator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Objects;

/**
 * Application use case orchestrating Confluence scans and PII detection. What: encapsulates
 * business/reactive flow away from the web controller. Returns ScanEvent stream that the
 * presentation layer can turn into SSE.
 */
@Slf4j
public class StreamConfluenceResumeScanUseCase extends
    AbstractStreamConfluenceScanUseCase implements StreamConfluenceResumeScanPort {

    private final ScanCheckpointRepository scanCheckpointRepository;

    public StreamConfluenceResumeScanUseCase(
        ConfluenceAccessor confluenceAccessor,
        PiiDetectorClient piiDetectorClient,
        ContentScanOrchestrator contentScanOrchestrator,
        AttachmentProcessor attachmentProcessor,
        ScanCheckpointRepository scanCheckpointRepository,
        ScanTimeOutConfig scanTimeoutConfig,
        HtmlContentParser htmlContentParser) {
        super(confluenceAccessor, piiDetectorClient, contentScanOrchestrator, attachmentProcessor, scanTimeoutConfig, htmlContentParser);
        this.scanCheckpointRepository = scanCheckpointRepository;
    }


    @Override
    public Flux<ContentScanResult> resumeAllSpaces(String scanId) {
        if (isBlank(scanId)) {
            return Flux.empty();
        }
        // Atomically set PAUSED checkpoints back to RUNNING BEFORE emitting scan events,
        // so the UPSERT guard (which blocks PAUSED → RUNNING from scan events) does not reject them.
        // Wrapped in Mono.fromCallable to run on boundedElastic (JPA is blocking).
        return Mono.fromCallable(() -> scanCheckpointRepository.resumeAllPausedCheckpoints(scanId))
            .subscribeOn(Schedulers.boundedElastic())
            .doOnNext(resumed ->
                log.info("[RESUME] Scan {} — {} checkpoint(s) updated from PAUSED to RUNNING", scanId, resumed))
            .then(Mono.fromFuture(confluenceAccessor.getAllSpaces()))
            .flatMapMany(spaces ->
                             Flux.fromIterable(spaces)
                                 .concatMap(space -> resumeScanResultFlux(scanId, space)))
            .onErrorResume(exception -> {
                log.error("[USECASE] Error when resuming scan: {}", exception.getMessage(),
                          exception);
                return buildErrorScanResultFlux(scanId, null, exception);
            });
    }

    private Flux<ContentScanResult> resumeScanResultFlux(String scanId, ConfluenceSpace space) {
        try {
            var scanCheckpoint = scanCheckpointRepository.findByScanAndSource(scanId, SourceType.CONFLUENCE, space.key())
                .orElse(null);
            Flux<ContentScanResult> empty = checkScanCompletionAndGenerateFlux(scanCheckpoint);
            return Objects.requireNonNullElseGet(empty, () -> Mono.fromFuture(
                    confluenceAccessor.getAllPagesInSpace(space.key()))
                .flatMapMany(pages -> {
                    ScanRemainingPages scanRemainingPages =
                        ScanRemainingPagesCalculator.computeRemainPages(pages,
                                                                        scanCheckpoint);
                    if (scanRemainingPages.remaining().isEmpty()) {
                        return Flux.empty();
                    }
                    return runScanFlux(scanId, space.key(), scanRemainingPages.remaining(),
                                       scanRemainingPages.analyzedOffset(),
                                       scanRemainingPages.originalTotal());
                })
                .onErrorResume(exception -> {
                    log.error("[USECASE] Error when resuming scan of space {}: {}",
                              space.key(), exception.getMessage(), exception);
                    return buildErrorScanResultFlux(scanId, space, exception);
                }));
        } catch (Exception exception) {
            log.error("[USECASE] Error when resuming scan of space {}: {}",
                      space.key(), exception.getMessage(), exception);
            return buildErrorScanResultFlux(scanId, space, exception);
        }
    }

    private static Flux<ContentScanResult> checkScanCompletionAndGenerateFlux(
        ScanCheckpoint checkpointOptional) {
        if (checkpointOptional != null && checkpointOptional.scanStatus() == ScanStatus.COMPLETED) {
            return Flux.empty();
        }
        return null;
    }

    private static Flux<ContentScanResult> buildErrorScanResultFlux(String scanId, ConfluenceSpace space,
                                                                              Throwable exception) {
        return Flux.just(ContentScanResult.builder()
                             .scanId(scanId)
                             .sourceId(space != null ? space.key() : null)
                             .eventType(DetectionReportingEventType.ERROR.getLabel())
                             .message(resolveErrorMessage(exception))
                             .emittedAt(Instant.now().toString())
                             .build());
    }
}
