package pro.softcom.aisentinel.application.sharepoint.port.in;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Port IN for streaming SharePoint PII scan results.
 */
public interface StreamSharePointScanPort {
    Flux<ContentScanResult> scanAllSites();
    Flux<ContentScanResult> scanSelectedSites(List<String> siteIds);
}
