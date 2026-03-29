package pro.softcom.aisentinel.application.pii.reporting.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.pii.reporting.port.in.ScanReportingPort;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.*;
import pro.softcom.aisentinel.domain.pii.scan.ConfluenceSpaceScanState;

import java.time.Instant;
import java.util.*;

@RequiredArgsConstructor
@Slf4j
public class ScanReportingUseCase implements ScanReportingPort {

    private final ScanResultQuery scanResultQuery;
    private final ScanCheckpointRepository checkpointRepo;

    @Override
    public Optional<LastScanMeta> getLatestScan() {
        try {
            return scanResultQuery.findLatestScan();
        } catch (Exception ex) {
            log.warn("[LAST_SCAN] Failed to get latest scan: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ConfluenceSpaceScanState> getLatestSpaceScanStateList(String scanId) {
        if (scanId == null || scanId.isBlank()) return List.of();

        CheckpointIndex index = loadCheckpointIndex(scanId);

        try {
            return scanResultQuery.getSpaceCounters(scanId).stream()
                .map(c -> new ConfluenceSpaceScanState(
                    c.sourceKey(),
                    mapPresentationStatus(index.statuses().get(c.sourceKey()), c.pagesDone(), c.attachmentsDone()),
                    c.pagesDone(),
                    c.attachmentsDone(),
                    c.lastEventTs(),
                    index.progressPercentages().get(c.sourceKey())
                ))
                .toList();
        } catch (Exception ex) {
            log.warn("[LAST_SCAN] Failed to get space statuses for {}: {}", scanId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ContentScanResult> getLatestSpaceScanResultList() {
        try {
            Optional<LastScanMeta> meta = scanResultQuery.findLatestScan();
            if (meta.isEmpty()) return List.of();
            return scanResultQuery.listItemEventsEncrypted(meta.get().scanId());
        } catch (Exception ex) {
            log.warn("[LAST_SCAN] Failed to get latest scan items: {}", ex.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ContentScanResult> getGlobalScanItemsEncrypted() {
        try {
            // 1) Find the latest checkpoint for every space
            List<ScanCheckpoint> latestCheckpoints = checkpointRepo.findAllLatestCheckpoints();
            log.info("[SCAN] Found {} latest checkpoints for global items aggregation", latestCheckpoints.size());
            List<ContentScanResult> allItems = new ArrayList<>();

            for (ScanCheckpoint cp : latestCheckpoints) {
                log.info("[SCAN] Processing checkpoint: source={}, scanId={}", cp.sourceKey(), cp.scanId());
                // 2) Load items for this specific (scanId, sourceKey) pair
                List<ContentScanResult> spaceItems = scanResultQuery.listItemEventsEncryptedBySourceKey(
                    cp.scanId(),
                    cp.sourceKey()
                );
                log.info("[SCAN] Found {} items for source={}, scanId={}", spaceItems.size(), cp.sourceKey(), cp.scanId());
                allItems.addAll(spaceItems);
            }
            return allItems;
        } catch (Exception ex) {
            log.warn("[SCAN] Failed to get global scan items: {}", ex.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<ScanReportingSummary> getScanReportingSummary(String scanId) {
        if (scanId == null || scanId.isBlank()) return Optional.empty();

        try {
            CheckpointIndex index = loadCheckpointIndex(scanId);

            List<SpaceSummary> spaces = scanResultQuery.getSpaceCounters(scanId).stream()
                .map(c -> new SpaceSummary(
                    c.sourceKey(),
                    mapPresentationStatus(index.statuses().get(c.sourceKey()), c.pagesDone(), c.attachmentsDone()),
                    index.progressPercentages().get(c.sourceKey()),
                    c.pagesDone(),
                    c.attachmentsDone(),
                    c.lastEventTs()
                ))
                .toList();

            Instant lastUpdated = spaces.stream()
                .map(SpaceSummary::lastEventTs)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(Instant.now());

            return Optional.of(new ScanReportingSummary(
                scanId,
                lastUpdated,
                spaces.size(),
                spaces
            ));
        } catch (Exception ex) {
            log.warn("[SCAN] Failed to get dashboard ScanReportingSummary for {}: {}", scanId, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<ScanReportingSummary> getGlobalScanSummary() {
        try {
            List<ScanCheckpoint> latestCheckpoints = checkpointRepo.findAllLatestCheckpoints();
            if (latestCheckpoints.isEmpty()) {
                return Optional.empty();
            }

            Set<String> scanIds = new HashSet<>();
            for (ScanCheckpoint cp : latestCheckpoints) {
                scanIds.add(cp.scanId());
            }

            Map<String, List<ScanResultQuery.SpaceCounter>> countersByScan = new HashMap<>();
            for (String scanId : scanIds) {
                countersByScan.put(scanId, scanResultQuery.getSpaceCounters(scanId));
            }

            List<SpaceSummary> spaces = new ArrayList<>();
            for (ScanCheckpoint cp : latestCheckpoints) {
                List<ScanResultQuery.SpaceCounter> scanCounters = countersByScan.get(cp.scanId());

                long pagesDone = 0;
                long attachmentsDone = 0;
                Instant lastEventTs = null;

                if (scanCounters != null) {
                    for (ScanResultQuery.SpaceCounter sc : scanCounters) {
                        if (sc.sourceKey().equals(cp.sourceKey())) {
                            pagesDone = sc.pagesDone();
                            attachmentsDone = sc.attachmentsDone();
                            lastEventTs = sc.lastEventTs();
                            break;
                        }
                    }
                }

                spaces.add(new SpaceSummary(
                    cp.sourceKey(),
                    mapPresentationStatus(cp.scanStatus(), pagesDone, attachmentsDone),
                    cp.progressPercentage(),
                    pagesDone,
                    attachmentsDone,
                    lastEventTs
                ));
            }

            Optional<LastScanMeta> latestMeta = getLatestScan();
            String globalScanId = latestMeta.map(LastScanMeta::scanId).orElse(
                latestCheckpoints.getFirst().scanId()
            );

            Instant globalLastUpdated = spaces.stream()
                .map(SpaceSummary::lastEventTs)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(Instant.now());

            return Optional.of(new ScanReportingSummary(
                globalScanId,
                globalLastUpdated,
                spaces.size(),
                spaces
            ));

        } catch (Exception ex) {
            log.warn("[SCAN] Failed to get global ScanReportingSummary: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private CheckpointIndex loadCheckpointIndex(String scanId) {
        Map<String, ScanStatus> statuses = new HashMap<>();
        Map<String, Double> progressPercentages = new HashMap<>();
        try {
            List<ScanCheckpoint> cps = checkpointRepo.findByScan(scanId);
            for (ScanCheckpoint cp : cps) {
                statuses.put(cp.sourceKey(), cp.scanStatus());
                progressPercentages.put(cp.sourceKey(), cp.progressPercentage());
            }
        } catch (Exception ex) {
            log.warn("[LAST_SCAN] Failed to load checkpoint statuses: {}", ex.getMessage());
        }
        return new CheckpointIndex(statuses, progressPercentages);
    }

    private ReportingScanStatus mapPresentationStatus(ScanStatus checkpointStatus, long pagesDone, long attachmentsDone) {
        if (checkpointStatus != null) {
            return switch (checkpointStatus) {
                case COMPLETED -> ReportingScanStatus.COMPLETED;
                case FAILED -> ReportingScanStatus.FAILED;
                case RUNNING -> ReportingScanStatus.RUNNING;
                case PAUSED -> ReportingScanStatus.PAUSED;
                case NOT_STARTED -> ReportingScanStatus.PENDING;
            };
        }
        long progress = Math.max(0, pagesDone) + Math.max(0, attachmentsDone);
        return progress > 0 ? ReportingScanStatus.PAUSED : ReportingScanStatus.PENDING;
    }

    @Override
    public Optional<LastScanMeta> getLatestScanBySourceType(SourceType sourceType) {
        try {
            return scanResultQuery.findLatestScanBySourceType(sourceType);
        } catch (Exception ex) {
            log.warn("[LAST_SCAN] Failed to get latest scan for {}: {}", sourceType, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<ScanReportingSummary> getScanSummaryBySourceType(SourceType sourceType) {
        try {
            List<ScanCheckpoint> latestCheckpoints = checkpointRepo.findAllLatestCheckpointsBySourceType(sourceType);
            if (latestCheckpoints.isEmpty()) {
                return Optional.empty();
            }

            Set<String> scanIds = new HashSet<>();
            for (ScanCheckpoint cp : latestCheckpoints) {
                scanIds.add(cp.scanId());
            }

            Map<String, List<ScanResultQuery.SpaceCounter>> countersByScan = new HashMap<>();
            for (String scanId : scanIds) {
                countersByScan.put(scanId, scanResultQuery.getSpaceCounters(scanId));
            }

            List<SpaceSummary> spaces = new ArrayList<>();
            for (ScanCheckpoint cp : latestCheckpoints) {
                List<ScanResultQuery.SpaceCounter> scanCounters = countersByScan.get(cp.scanId());

                long pagesDone = 0;
                long attachmentsDone = 0;
                Instant lastEventTs = null;

                if (scanCounters != null) {
                    for (ScanResultQuery.SpaceCounter sc : scanCounters) {
                        if (sc.sourceKey().equals(cp.sourceKey())) {
                            pagesDone = sc.pagesDone();
                            attachmentsDone = sc.attachmentsDone();
                            lastEventTs = sc.lastEventTs();
                            break;
                        }
                    }
                }

                spaces.add(new SpaceSummary(
                    cp.sourceKey(),
                    mapPresentationStatus(cp.scanStatus(), pagesDone, attachmentsDone),
                    cp.progressPercentage(),
                    pagesDone,
                    attachmentsDone,
                    lastEventTs
                ));
            }

            Optional<LastScanMeta> latestMeta = getLatestScanBySourceType(sourceType);
            String globalScanId = latestMeta.map(LastScanMeta::scanId).orElse(
                latestCheckpoints.getFirst().scanId()
            );

            Instant globalLastUpdated = spaces.stream()
                .map(SpaceSummary::lastEventTs)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(Instant.now());

            return Optional.of(new ScanReportingSummary(
                globalScanId,
                globalLastUpdated,
                spaces.size(),
                spaces
            ));

        } catch (Exception ex) {
            log.warn("[SCAN] Failed to get scan summary for {}: {}", sourceType, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<ContentScanResult> getScanItemsBySourceType(SourceType sourceType) {
        try {
            List<ScanCheckpoint> latestCheckpoints = checkpointRepo.findAllLatestCheckpointsBySourceType(sourceType);
            List<ContentScanResult> allItems = new ArrayList<>();

            for (ScanCheckpoint cp : latestCheckpoints) {
                List<ContentScanResult> spaceItems = scanResultQuery.listItemEventsEncryptedBySourceKey(
                    cp.scanId(),
                    cp.sourceKey()
                );
                allItems.addAll(spaceItems);
            }
            return allItems;
        } catch (Exception ex) {
            log.warn("[SCAN] Failed to get scan items for {}: {}", sourceType, ex.getMessage());
            return List.of();
        }
    }

    private record CheckpointIndex(Map<String, ScanStatus> statuses, Map<String, Double> progressPercentages) { }
}
