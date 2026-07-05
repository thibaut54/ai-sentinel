package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto;

import java.time.Instant;
import java.util.List;

/**
 * Dashboard tooltip payload aggregating the latest scan's stats for a space.
 *
 * @param scanId             scan identifier
 * @param spaceKey           Confluence space key
 * @param startedAt          scan start timestamp
 * @param finishedAt         scan finish timestamp, or null while running
 * @param durationMs         wall-clock duration in milliseconds, or null while running
 * @param pagesScanned       number of pages successfully analyzed
 * @param pagesFailed        number of pages that failed analysis
 * @param pageChars          total characters of page content analyzed
 * @param attachmentsScanned number of attachments successfully analyzed
 * @param attachmentsFailed  number of attachments that failed analysis
 * @param attachmentChars    total characters of attachment content analyzed
 * @param failedItems        failed pages/attachments
 * @param detectorStats      per-detector throughput stats
 */
public record ScanSpaceStatsDto(
    String scanId,
    String spaceKey,
    Instant startedAt,
    Instant finishedAt,
    Long durationMs,
    int pagesScanned,
    int pagesFailed,
    long pageChars,
    int attachmentsScanned,
    int attachmentsFailed,
    long attachmentChars,
    List<FailedScanItemDto> failedItems,
    List<ScanDetectorStatDto> detectorStats
) { }
