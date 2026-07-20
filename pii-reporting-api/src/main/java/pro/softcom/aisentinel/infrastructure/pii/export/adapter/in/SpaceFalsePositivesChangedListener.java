package pro.softcom.aisentinel.infrastructure.pii.export.adapter.in;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.pii.export.port.in.ExportDetectionReportPort;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.remediation.SpaceFalsePositivesChanged;

/**
 * Regenerates the detection report of a space when its false-positive set changes,
 * so the exported file stays consistent with the remediation review. Failures are
 * swallowed: the status change already succeeded and the report is regenerated on
 * the next false-positive change or scan of the space.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpaceFalsePositivesChangedListener {

    private final ExportDetectionReportPort exportDetectionReportPort;

    @EventListener
    public void onSpaceFalsePositivesChanged(SpaceFalsePositivesChanged event) {
        if (event == null) {
            log.warn("Received a null event");
            return;
        }

        log.info("Received SpaceFalsePositivesChanged: scanId={}, spaceKey={}",
                event.scanId(), event.spaceKey());

        try {
            exportDetectionReportPort.export(event.scanId(), SourceType.CONFLUENCE, event.spaceKey());
        } catch (Exception ex) {
            log.error("Failed to regenerate report for scanId={}, spaceKey={}: {}",
                    event.scanId(), event.spaceKey(), ex.getMessage(), ex);
        }
    }
}
