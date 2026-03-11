package pro.softcom.aisentinel.application.pii.export.port.out;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;

import java.util.stream.Stream;

public interface ReadScanEventsPort {
    Stream<ContentScanResult> streamByScanIdAndSourceKey(String scanId, String sourceKey);
}
