package pro.softcom.aisentinel.application.pii.reporting.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceSpaceRepository;
import pro.softcom.aisentinel.application.pii.reporting.DashboardFilterCriteria;
import pro.softcom.aisentinel.application.pii.reporting.DashboardSpaceFilter;
import pro.softcom.aisentinel.application.pii.reporting.ScanPiiTypeCountService;
import pro.softcom.aisentinel.application.pii.reporting.ScanSeverityCountService;
import pro.softcom.aisentinel.application.pii.reporting.port.in.ScanReportingPort;
import pro.softcom.aisentinel.application.pii.reporting.port.out.ScanResultQuery;
import pro.softcom.aisentinel.application.pii.scan.port.out.ScanCheckpointRepository;
import pro.softcom.aisentinel.domain.confluence.ConfluenceSpace;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.reporting.*;
import pro.softcom.aisentinel.domain.pii.scan.ConfluenceSpaceScanState;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
public class ScanReportingUseCase implements ScanReportingPort {

    private final ScanResultQuery scanResultQuery;
    private final ScanCheckpointRepository checkpointRepo;
    private final ConfluenceSpaceRepository spaceRepository;
    private final ScanSeverityCountService severityCountService;
    private final ScanPiiTypeCountService piiTypeCountService;

    /**
     * Builds a lookup map of space key to space name from the cached Confluence spaces.
     *
     * <p>Built once per call to avoid N+1 lookups when enriching space summaries.
     *
     * @return Map of space key to space name (never null, may be empty)
     */
    private Map<String, String> buildSpaceNameMap() {
        try {
            return spaceRepository.findAll().stream()
                .collect(Collectors.toMap(
                    ConfluenceSpace::key,
                    ConfluenceSpace::name,
                    (existing, replacement) -> existing));
        } catch (Exception ex) {
            log.warn("[SCAN] Failed to load space names: {}", ex.getMessage());
            return Map.of();
        }
    }

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

        // 1) Load checkpoint statuses and progress percentages (may be empty if no checkpoint yet for a space)
        Map<String, ScanStatus> statuses = new HashMap<>();
        Map<String, Double> progressPercentages = new HashMap<>();
        try {
            List<ScanCheckpoint> cps = checkpointRepo.findByScan(scanId);
            for (ScanCheckpoint cp : cps) {
                statuses.put(cp.spaceKey(), cp.scanStatus());
                progressPercentages.put(cp.spaceKey(), cp.progressPercentage());
            }
        } catch (Exception ex) {
            log.warn("[LAST_SCAN] Failed to load checkpoint statuses: {}", ex.getMessage());
        }

        // 2) Load counters from events per space via read port
        try {
            return scanResultQuery.getSpaceCounters(scanId).stream()
                .map(c -> new ConfluenceSpaceScanState(
                    c.spaceKey(),
                    mapPresentationStatus(statuses.get(c.spaceKey()), c.pagesDone(), c.attachmentsDone()),
                    c.pagesDone(),
                    c.attachmentsDone(),
                    c.lastEventTs(),
                    progressPercentages.get(c.spaceKey())
                ))
                .toList();
        } catch (Exception ex) {
            log.warn("[LAST_SCAN] Failed to get space statuses for {}: {}", scanId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ConfluenceContentScanResult> getLatestSpaceScanResultList() {
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
    public List<ConfluenceContentScanResult> getGlobalScanItemsEncrypted() {
        try {
            // 1) Find the latest checkpoint for every space
            List<ScanCheckpoint> latestCheckpoints = checkpointRepo.findAllLatestCheckpoints();
            log.info("[SCAN] Found {} latest checkpoints for global items aggregation", latestCheckpoints.size());
            List<ConfluenceContentScanResult> allItems = new ArrayList<>();

            for (ScanCheckpoint cp : latestCheckpoints) {
                log.info("[SCAN] Processing checkpoint: space={}, scanId={}", cp.spaceKey(), cp.scanId());
                // 2) Load items for this specific (scanId, spaceKey) pair
                List<ConfluenceContentScanResult> spaceItems = scanResultQuery.listItemEventsEncryptedByScanIdAndSpaceKey(
                    cp.scanId(),
                    cp.spaceKey()
                );
                log.info("[SCAN] Found {} items for space={}, scanId={}", spaceItems.size(), cp.spaceKey(), cp.scanId());
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
            // 1) Load checkpoint statuses and progress percentages
            Map<String, ScanStatus> statuses = new HashMap<>();
            Map<String, Double> progressPercentages = new HashMap<>();
            List<ScanCheckpoint> cps = checkpointRepo.findByScan(scanId);
            for (ScanCheckpoint cp : cps) {
                statuses.put(cp.spaceKey(), cp.scanStatus());
                progressPercentages.put(cp.spaceKey(), cp.progressPercentage());
            }

            // 2) Build batch lookups once to avoid N+1 lookups
            Map<String, String> spaceNames = buildSpaceNameMap();
            Map<String, SeverityCounts> severityByKey = loadSeverityCounts(scanId);
            Map<String, Map<String, Integer>> piiTypeByKey = loadPiiTypeCounts(scanId);

            // 3) Load counters from events per space
            List<SpaceSummary> spaces = scanResultQuery.getSpaceCounters(scanId).stream()
                .map(c -> new SpaceSummary(
                    c.spaceKey(),
                    mapPresentationStatus(statuses.get(c.spaceKey()), c.pagesDone(), c.attachmentsDone()),
                    progressPercentages.get(c.spaceKey()),
                    c.pagesDone(),
                    c.attachmentsDone(),
                    c.lastEventTs(),
                    spaceNames.get(c.spaceKey()),
                    severityByKey.getOrDefault(c.spaceKey(), SeverityCounts.zero()),
                    piiTypeByKey.getOrDefault(c.spaceKey(), Map.of())
                ))
                .toList();

            // 4) Find most recent timestamp
            Instant lastUpdated = mostRecentTimestamp(spaces);

            return Optional.of(new ScanReportingSummary(scanId, lastUpdated, spaces.size(), spaces,
                DashboardFacets.empty()));
        } catch (Exception ex) {
            log.warn("[SCAN] Failed to get dashboard ScanReportingSummary for {}: {}", scanId, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<ScanReportingSummary> getGlobalScanSummary(DashboardFilterCriteria criteria) {
        try {
            List<ScanCheckpoint> latestCheckpoints = checkpointRepo.findAllLatestCheckpoints();
            List<ConfluenceSpace> allSpaces = loadAllSpaces();
            if (latestCheckpoints.isEmpty() && allSpaces.isEmpty()) {
                return Optional.empty();
            }

            List<SpaceSummary> spaces = buildUnifiedSpaces(latestCheckpoints, allSpaces);
            DashboardSpaceFilter.Result result = DashboardSpaceFilter.apply(spaces, criteria);

            String globalScanId = resolveGlobalScanId(latestCheckpoints);
            Instant lastUpdated = mostRecentTimestamp(spaces);

            return Optional.of(new ScanReportingSummary(
                globalScanId,
                lastUpdated,
                spaces.size(),
                result.spaces(),
                result.facets()
            ));
        } catch (Exception ex) {
            log.warn("[SCAN] Failed to get global ScanReportingSummary: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Builds the unified list of ALL spaces: every Confluence space plus any scanned space not in the
     * cache, each left-joined with its latest scan data (status, progress, counters, counts).
     */
    private List<SpaceSummary> buildUnifiedSpaces(List<ScanCheckpoint> latestCheckpoints,
                                                  List<ConfluenceSpace> allSpaces) {
        Map<String, ScanCheckpoint> checkpointByKey = indexCheckpointsByKey(latestCheckpoints);
        Map<String, ScanResultQuery.SpaceCounter> counterByKey = loadCountersByKey(checkpointByKey);
        Map<String, SeverityCounts> severityByKey = loadSeverityCounts(checkpointByKey);
        Map<String, Map<String, Integer>> piiTypeByKey = loadPiiTypeCounts(checkpointByKey);

        Set<String> keys = new LinkedHashSet<>();
        allSpaces.forEach(space -> keys.add(space.key()));
        keys.addAll(checkpointByKey.keySet());

        Map<String, String> spaceNames = allSpaces.stream()
            .collect(Collectors.toMap(ConfluenceSpace::key, ConfluenceSpace::name, (a, b) -> a));

        List<SpaceSummary> spaces = new ArrayList<>();
        for (String key : keys) {
            spaces.add(buildSpaceSummary(key, checkpointByKey.get(key), counterByKey.get(key),
                spaceNames.get(key), severityByKey.getOrDefault(key, SeverityCounts.zero()),
                piiTypeByKey.getOrDefault(key, Map.of())));
        }
        return spaces;
    }

    private SpaceSummary buildSpaceSummary(String key, ScanCheckpoint checkpoint,
                                           ScanResultQuery.SpaceCounter counter, String spaceName,
                                           SeverityCounts severityCounts, Map<String, Integer> piiTypeCounts) {
        long pagesDone = counter != null ? counter.pagesDone() : 0L;
        long attachmentsDone = counter != null ? counter.attachmentsDone() : 0L;
        Instant lastEventTs = counter != null ? counter.lastEventTs() : null;
        ScanStatus checkpointStatus = checkpoint != null ? checkpoint.scanStatus() : null;
        Double progress = checkpoint != null ? checkpoint.progressPercentage() : null;

        return new SpaceSummary(
            key,
            mapPresentationStatus(checkpointStatus, pagesDone, attachmentsDone),
            progress,
            pagesDone,
            attachmentsDone,
            lastEventTs,
            spaceName,
            severityCounts,
            piiTypeCounts
        );
    }

    private Map<String, ScanCheckpoint> indexCheckpointsByKey(List<ScanCheckpoint> checkpoints) {
        Map<String, ScanCheckpoint> byKey = new LinkedHashMap<>();
        for (ScanCheckpoint cp : checkpoints) {
            byKey.put(cp.spaceKey(), cp);
        }
        return byKey;
    }

    /**
     * Loads per-space counters for the relevant scanIds, keyed by space key.
     * Uses one {@code getSpaceCounters} call per distinct scanId (no N+1 per space).
     */
    private Map<String, ScanResultQuery.SpaceCounter> loadCountersByKey(Map<String, ScanCheckpoint> checkpointByKey) {
        Map<String, ScanResultQuery.SpaceCounter> result = new HashMap<>();
        Set<String> scanIds = checkpointByKey.values().stream()
            .map(ScanCheckpoint::scanId)
            .collect(Collectors.toSet());
        Map<String, List<ScanResultQuery.SpaceCounter>> countersByScan = new HashMap<>();
        for (String scanId : scanIds) {
            countersByScan.put(scanId, scanResultQuery.getSpaceCounters(scanId));
        }
        for (ScanCheckpoint cp : checkpointByKey.values()) {
            findCounter(countersByScan.get(cp.scanId()), cp.spaceKey())
                .ifPresent(counter -> result.put(cp.spaceKey(), counter));
        }
        return result;
    }

    private Optional<ScanResultQuery.SpaceCounter> findCounter(List<ScanResultQuery.SpaceCounter> counters,
                                                               String spaceKey) {
        if (counters == null) {
            return Optional.empty();
        }
        return counters.stream().filter(counter -> counter.spaceKey().equals(spaceKey)).findFirst();
    }

    /**
     * Loads severity counts for each space's latest scanId, keyed by space key.
     * Batches one {@code findByScanId} per distinct scanId.
     */
    private Map<String, SeverityCounts> loadSeverityCounts(Map<String, ScanCheckpoint> checkpointByKey) {
        Map<String, SeverityCounts> result = new HashMap<>();
        Map<String, Map<String, SeverityCounts>> byScan = new HashMap<>();
        for (ScanCheckpoint cp : checkpointByKey.values()) {
            Map<String, SeverityCounts> forScan = byScan.computeIfAbsent(cp.scanId(), this::loadSeverityCounts);
            SeverityCounts counts = forScan.get(cp.spaceKey());
            if (counts != null) {
                result.put(cp.spaceKey(), counts);
            }
        }
        return result;
    }

    private Map<String, SeverityCounts> loadSeverityCounts(String scanId) {
        return severityCountService.getCountsByScan(scanId).stream()
            .collect(Collectors.toMap(ScanSeverityCount::spaceKey, ScanSeverityCount::counts, (a, b) -> a));
    }

    /**
     * Loads PII type counts for each space's latest scanId, keyed by space key.
     * Batches one {@code findByScanId} per distinct scanId.
     */
    private Map<String, Map<String, Integer>> loadPiiTypeCounts(Map<String, ScanCheckpoint> checkpointByKey) {
        Map<String, Map<String, Integer>> result = new HashMap<>();
        Map<String, Map<String, Map<String, Integer>>> byScan = new HashMap<>();
        for (ScanCheckpoint cp : checkpointByKey.values()) {
            Map<String, Map<String, Integer>> forScan = byScan.computeIfAbsent(cp.scanId(), this::loadPiiTypeCounts);
            Map<String, Integer> counts = forScan.get(cp.spaceKey());
            if (counts != null) {
                result.put(cp.spaceKey(), counts);
            }
        }
        return result;
    }

    private Map<String, Map<String, Integer>> loadPiiTypeCounts(String scanId) {
        return piiTypeCountService.getCountsByScan(scanId).stream()
            .collect(Collectors.toMap(ScanPiiTypeCount::spaceKey, ScanPiiTypeCount::countsByType, (a, b) -> a));
    }

    private List<ConfluenceSpace> loadAllSpaces() {
        try {
            return spaceRepository.findAll();
        } catch (Exception ex) {
            log.warn("[SCAN] Failed to load Confluence spaces: {}", ex.getMessage());
            return List.of();
        }
    }

    private String resolveGlobalScanId(List<ScanCheckpoint> latestCheckpoints) {
        Optional<LastScanMeta> latestMeta = getLatestScan();
        if (latestMeta.isPresent()) {
            return latestMeta.get().scanId();
        }
        return latestCheckpoints.isEmpty() ? null : latestCheckpoints.getFirst().scanId();
    }

    private Instant mostRecentTimestamp(List<SpaceSummary> spaces) {
        return spaces.stream()
            .map(SpaceSummary::lastEventTs)
            .filter(Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(Instant.now());
    }

    private String mapPresentationStatus(ScanStatus checkpointStatus, long pagesDone, long attachmentsDone) {
        if (checkpointStatus != null) {
            return switch (checkpointStatus) {
                case COMPLETED, FAILED, RUNNING -> checkpointStatus.name();
                case PAUSED -> "PAUSED";
                case NOT_STARTED -> "NOT_STARTED";
            };
        }
        long progress = Math.max(0, pagesDone) + Math.max(0, attachmentsDone);
        return progress > 0 ? "PAUSED" : "NOT_STARTED";
    }
}
