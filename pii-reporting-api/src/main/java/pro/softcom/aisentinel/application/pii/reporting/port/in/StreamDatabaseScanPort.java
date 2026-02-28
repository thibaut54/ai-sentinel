package pro.softcom.aisentinel.application.pii.reporting.port.in;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import reactor.core.publisher.Flux;

public interface StreamDatabaseScanPort {

    Flux<ContentScanResult> streamScan(ScanSourceConfig config);
}
