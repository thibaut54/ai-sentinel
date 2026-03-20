package pro.softcom.aisentinel.application.pii.reporting.port.in;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import reactor.core.publisher.Flux;

public interface StreamConfluenceResumeScanPort {

    Flux<ContentScanResult> resumeAllSpaces(String scanId);
}
