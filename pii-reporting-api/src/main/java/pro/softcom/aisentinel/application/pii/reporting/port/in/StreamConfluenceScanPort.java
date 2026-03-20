package pro.softcom.aisentinel.application.pii.reporting.port.in;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import reactor.core.publisher.Flux;

import java.util.List;

public interface StreamConfluenceScanPort {

    Flux<ContentScanResult> streamSpace(String spaceKey);

    Flux<ContentScanResult> streamAllSpaces();

    Flux<ContentScanResult> streamSelectedSpaces(List<String> spaceKeys);
}
