package pro.softcom.aisentinel.application.pii.reporting.usecase;

import pro.softcom.aisentinel.application.confluence.service.ConfluenceAccessor;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanTimeOutConfig;
import pro.softcom.aisentinel.application.pii.reporting.service.AttachmentProcessor;
import pro.softcom.aisentinel.application.pii.reporting.service.ContentScanOrchestrator;
import pro.softcom.aisentinel.application.pii.reporting.service.ScanSpaceStatsCollector;
import pro.softcom.aisentinel.application.pii.reporting.service.parser.HtmlContentParser;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;

/**
 * Cohesive bundle of the collaborators shared by every Confluence scan use case.
 *
 * <p>Groups the seven pipeline dependencies that {@link AbstractStreamConfluenceScanUseCase}
 * needs so concrete use cases stay below the parameter-count limit while only declaring
 * their own extra collaborators.
 *
 * @param confluenceAccessor      access to Confluence spaces, pages and attachments
 * @param piiDetectorClient       client invoking the PII detector
 * @param contentScanOrchestrator orchestrates event creation, progress and persistence
 * @param attachmentProcessor     extracts text from page attachments
 * @param scanTimeoutConfig       per-call PII detection timeouts
 * @param htmlContentParser       cleans raw HTML page content before detection
 * @param scanSpaceStatsCollector accumulates per-space scan statistics
 * @param pageConcurrency         number of pages whose PII detection runs
 *                                concurrently (1 = sequential, the historical
 *                                behaviour); feeds the detector worker pool
 */
public record ScanPipelineDependencies(
    ConfluenceAccessor confluenceAccessor,
    PiiDetectorClient piiDetectorClient,
    ContentScanOrchestrator contentScanOrchestrator,
    AttachmentProcessor attachmentProcessor,
    ScanTimeOutConfig scanTimeoutConfig,
    HtmlContentParser htmlContentParser,
    ScanSpaceStatsCollector scanSpaceStatsCollector,
    int pageConcurrency
) {

    /**
     * Backward-compatible constructor defaulting page concurrency to 1
     * (sequential page processing — the historical behaviour). Kept so existing
     * call sites (tests) compile unchanged; production wiring uses the canonical
     * constructor with the configured value.
     */
    public ScanPipelineDependencies(
        ConfluenceAccessor confluenceAccessor,
        PiiDetectorClient piiDetectorClient,
        ContentScanOrchestrator contentScanOrchestrator,
        AttachmentProcessor attachmentProcessor,
        ScanTimeOutConfig scanTimeoutConfig,
        HtmlContentParser htmlContentParser,
        ScanSpaceStatsCollector scanSpaceStatsCollector
    ) {
        this(confluenceAccessor, piiDetectorClient, contentScanOrchestrator, attachmentProcessor,
             scanTimeoutConfig, htmlContentParser, scanSpaceStatsCollector, 1);
    }
}
