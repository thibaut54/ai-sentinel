package pro.softcom.aisentinel.domain.pii.reporting;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain model representing a space summary in the dashboard.
 * Combines authoritative state from scan_checkpoints with aggregated counters from scan_events.
 */
public record SpaceSummary(
    String sourceKey,
    ReportingScanStatus status,
    Double progressPercentage,
    long pagesDone,
    long attachmentsDone,
    Instant lastEventTs
) {
    public SpaceSummary {
        Objects.requireNonNull(status, "status must not be null");
    }
}
