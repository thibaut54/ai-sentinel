package pro.softcom.aisentinel.domain.pii.scan;

import pro.softcom.aisentinel.domain.pii.reporting.ReportingScanStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Per-space status with simple progress counters.
 */
public record ConfluenceSpaceScanState(String sourceKey, ReportingScanStatus status, long pagesDone,
                                       long attachmentsDone, Instant lastEventTs, Double progressPercentage) {
    public ConfluenceSpaceScanState {
        Objects.requireNonNull(status, "status must not be null");
    }
}
