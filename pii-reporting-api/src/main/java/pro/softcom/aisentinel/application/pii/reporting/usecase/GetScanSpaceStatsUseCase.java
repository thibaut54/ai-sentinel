package pro.softcom.aisentinel.application.pii.reporting.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.pii.reporting.port.in.GetScanSpaceStatsPort;
import pro.softcom.aisentinel.application.pii.reporting.port.out.FailedScanItemQuery;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanSpaceStatsRepository;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.domain.pii.reporting.FailedScanItem;
import pro.softcom.aisentinel.domain.pii.reporting.ScanCheckpoint;
import pro.softcom.aisentinel.domain.pii.reporting.ScanDetectorStat;
import pro.softcom.aisentinel.domain.pii.reporting.ScanSpaceStats;

import java.util.List;
import java.util.Optional;

/**
 * Use case assembling the dashboard scan statistics of a space.
 *
 * <p>Business flow: resolves the latest scan of the space from its most recent
 * checkpoint, then aggregates the persisted stats, the per-detector breakdown
 * and the failed items into a single {@link ScanSpaceStats} read model.
 */
@RequiredArgsConstructor
@Slf4j
public class GetScanSpaceStatsUseCase implements GetScanSpaceStatsPort {

    /** Upper bound on the number of failed items returned for display. */
    static final int MAX_FAILED_ITEMS = 20;

    private final ScanCheckpointRepository checkpointRepository;
    private final ScanSpaceStatsRepository statsRepository;
    private final FailedScanItemQuery failedScanItemQuery;

    @Override
    public Optional<ScanSpaceStats> getLatestSpaceStats(String spaceKey) {
        if (spaceKey == null || spaceKey.isBlank()) {
            return Optional.empty();
        }
        Optional<String> scanId = resolveLatestScanId(spaceKey);
        if (scanId.isEmpty()) {
            return Optional.empty();
        }
        return statsRepository.findStats(scanId.get(), spaceKey)
            .map(stats -> enrich(stats, scanId.get(), spaceKey));
    }

    private Optional<String> resolveLatestScanId(String spaceKey) {
        return checkpointRepository.findLatestBySpace(spaceKey)
            .map(ScanCheckpoint::scanId);
    }

    private ScanSpaceStats enrich(ScanSpaceStats stats, String scanId, String spaceKey) {
        List<ScanDetectorStat> detectorStats = statsRepository.findDetectorStats(scanId, spaceKey);
        List<FailedScanItem> failedItems = failedScanItemQuery.findFailedItems(scanId, spaceKey, MAX_FAILED_ITEMS);
        return new ScanSpaceStats(
            stats.scanId(),
            stats.spaceKey(),
            stats.startedAt(),
            stats.finishedAt(),
            stats.pagesScanned(),
            stats.pagesFailed(),
            stats.pageChars(),
            stats.attachmentsScanned(),
            stats.attachmentsFailed(),
            stats.attachmentChars(),
            detectorStats,
            failedItems);
    }
}
