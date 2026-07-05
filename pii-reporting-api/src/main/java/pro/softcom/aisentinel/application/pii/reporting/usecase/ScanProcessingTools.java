package pro.softcom.aisentinel.application.pii.reporting.usecase;

import pro.softcom.aisentinel.application.pii.reporting.service.parser.HtmlContentParser;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiScanBenchRecorderPort;

/**
 * Bundles auxiliary processing tools used by Confluence scan use cases:
 * an HTML content parser for cleaning page bodies before PII detection
 * and a bench recorder for observability of detection performance.
 *
 * <p>Introduced to keep scan use case constructors below the 7-parameter
 * threshold (java:S107) without sacrificing dependency clarity.</p>
 */
public record ScanProcessingTools(
    HtmlContentParser htmlContentParser,
    PiiScanBenchRecorderPort benchRecorder
) {
}
