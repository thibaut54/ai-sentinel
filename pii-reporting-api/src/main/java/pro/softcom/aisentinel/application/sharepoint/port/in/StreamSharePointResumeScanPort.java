package pro.softcom.aisentinel.application.sharepoint.port.in;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import reactor.core.publisher.Flux;

/**
 * Port IN for resuming a paused SharePoint PII scan from the last checkpoint.
 */
public interface StreamSharePointResumeScanPort {
    Flux<ContentScanResult> resumeAllSites(String scanId);
}
