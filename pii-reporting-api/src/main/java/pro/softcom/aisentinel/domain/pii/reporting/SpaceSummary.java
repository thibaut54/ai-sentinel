package pro.softcom.aisentinel.domain.pii.reporting;

import java.time.Instant;
import java.util.Map;

/**
 * Domain model representing a space summary in the dashboard.
 * Combines authoritative state from scan_checkpoints with aggregated counters from scan_events.
 *
 * <p>Severity and per-type counts are embedded so that server-side filtering, sorting and
 * faceting can operate purely on the domain model without touching infrastructure.
 *
 * @param spaceKey           Confluence space key
 * @param status             backend scan status (e.g. COMPLETED, RUNNING, NOT_STARTED)
 * @param progressPercentage scan progress (may be null when never scanned)
 * @param pagesDone          number of pages processed
 * @param attachmentsDone    number of attachments processed
 * @param lastEventAt        timestamp of the last observed event (null when never scanned)
 * @param spaceName          human-readable space name (may be null)
 * @param severityCounts     aggregated severity counts (never null; zero when no detections)
 * @param piiTypeCounts      occurrence count keyed by PII type code (never null; empty when none)
 * @param scanId             identifier of the scan this space state belongs to (null when never scanned)
 */
public record SpaceSummary(
    String spaceKey,
    String status,
    Double progressPercentage,
    long pagesDone,
    long attachmentsDone,
    Instant lastEventAt,
    String spaceName,
    SeverityCounts severityCounts,
    Map<String, Integer> piiTypeCounts,
    String scanId
) {

    public SpaceSummary {
        if (severityCounts == null) {
            severityCounts = SeverityCounts.zero();
        }
        if (piiTypeCounts == null) {
            piiTypeCounts = Map.of();
        }
    }
}
