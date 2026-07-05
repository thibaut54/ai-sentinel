package pro.softcom.aisentinel.domain.pii.reporting;

import java.time.Instant;
import java.util.List;

/**
 * Aggregated scan statistics for a single Confluence space within a scan.
 *
 * <p>Business purpose: feeds the dashboard space tooltip with volume, failure
 * and per-detector throughput information for the latest scan of a space.
 *
 * <p>The scan duration is the wall-clock span ({@code finishedAt - startedAt}),
 * which is deliberately distinct from the per-detector cumulated busy time
 * exposed in {@link ScanDetectorStat#busyMs()}.
 *
 * @param scanId             unique identifier of the scan
 * @param spaceKey           Confluence space key
 * @param startedAt          moment the space scan started
 * @param finishedAt         moment the space scan completed, or null while running
 * @param pagesScanned       number of pages successfully analyzed
 * @param pagesFailed        number of pages that failed analysis
 * @param pageChars          total characters of page content analyzed
 * @param attachmentsScanned number of attachments successfully analyzed
 * @param attachmentsFailed  number of attachments that failed analysis
 * @param attachmentChars    total characters of attachment content analyzed
 * @param detectorStats      per-detector aggregated execution stats
 * @param failedItems        pages/attachments that failed during the scan
 */
public record ScanSpaceStats(
    String scanId,
    String spaceKey,
    Instant startedAt,
    Instant finishedAt,
    int pagesScanned,
    int pagesFailed,
    long pageChars,
    int attachmentsScanned,
    int attachmentsFailed,
    long attachmentChars,
    List<ScanDetectorStat> detectorStats,
    List<FailedScanItem> failedItems
) {

    /**
     * Wall-clock duration of the space scan in milliseconds.
     *
     * @return the elapsed time between start and finish, or null while the scan
     *         is still running (no finish timestamp yet)
     */
    public Long durationMs() {
        if (startedAt == null || finishedAt == null) {
            return null;
        }
        return finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }
}
