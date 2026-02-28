package pro.softcom.aisentinel.application.pii.export.port.out;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;

import java.util.stream.Stream;

public interface ReadScanEventsPort {
    Stream<ContentScanResult> streamByScanIdAndSpaceKey(String scanId, String spaceKey);
}
