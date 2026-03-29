package pro.softcom.aisentinel.application.pii.scan.port.out;

import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ScanCheckpoint;

import java.util.List;
import java.util.Optional;

/**
 * Application out port to store and retrieve scan checkpoints.
 * Business purpose: keeps a per-scan and per-source position so a scan can
 * resume, reconcile progress, or clean up its state.
 */
public interface ScanCheckpointRepository {

    /**
     * Persists or updates a checkpoint.
     *
     * @param checkpoint the business snapshot to record (scan id, source type, source key, position)
     */
    void save(ScanCheckpoint checkpoint);

    /**
     * Looks up the checkpoint for a given scan, source type and source key.
     *
     * @param scanId     the business identifier of the scan
     * @param sourceType the type of the datasource
     * @param sourceKey  the business key of the source (space key, project key, site id, etc.)
     * @return the checkpoint if present, otherwise empty
     */
    Optional<ScanCheckpoint> findByScanAndSource(String scanId, SourceType sourceType, String sourceKey);

    /**
     * Lists all checkpoints recorded for a scan.
     *
     * @param scanId the business identifier of the scan
     * @return checkpoints for the scan (may be empty)
     */
    List<ScanCheckpoint> findByScan(String scanId);

    /**
     * Lists all checkpoints recorded for a source across scans.
     *
     * @param sourceType the type of the datasource
     * @param sourceKey  the business key of the source
     * @return checkpoints for the source (may be empty)
     */
    List<ScanCheckpoint> findBySource(SourceType sourceType, String sourceKey);

    /**
     * Finds the most recent checkpoint for a given source across all scans.
     * Business purpose: Determine the last scan date for a source to check if it needs re-scanning.
     *
     * @param sourceType the type of the datasource
     * @param sourceKey  the business key of the source
     * @return the most recent checkpoint if present, otherwise empty
     */
    Optional<ScanCheckpoint> findLatestBySource(SourceType sourceType, String sourceKey);

    /**
     * Lists the most recent checkpoint for every source known in the system.
     * Business purpose: Build a global view of the latest state of all sources, even if they belong to different scans.
     *
     * @return list of latest checkpoints (one per source)
     */
    List<ScanCheckpoint> findAllLatestCheckpoints();

    /**
     * Lists the most recent checkpoint for every source of a given type.
     * Business purpose: Build a per-datasource view of the latest scan state.
     *
     * @param sourceType the type of the datasource to filter on
     * @return list of latest checkpoints (one per source of the given type)
     */
    List<ScanCheckpoint> findAllLatestCheckpointsBySourceType(SourceType sourceType);

    /**
     * Deletes all checkpoints for the given scan.
     *
     * @param scanId the business identifier of the scan
     */
    void deleteByScan(String scanId);

    /**
     * Deletes all active scan checkpoints (RUNNING or PAUSED status) for a given source type.
     * Business purpose: Clean up active scans when starting a fresh scan with the "Start" button.
     * This prevents accumulation of stale scan data and ensures severity counts don't get inflated
     * by mixing data from old and new scans.
     * Note: Completed scans (COMPLETED, FAILED status) are preserved as historical data.
     *
     * @param sourceType the type of the datasource to clean up
     */
    void deleteActiveScanCheckpointsBySourceType(SourceType sourceType);

    /**
     * Deletes ALL scan checkpoints for specific sources regardless of status.
     * Business purpose: When re-scanning selected sources, all previous checkpoint data
     * (including COMPLETED) must be removed so the dashboard summary does not return
     * stale statuses before the new scan creates its own checkpoints.
     *
     * @param sourceType the type of the datasource
     * @param sourceKeys list of source keys to purge
     */
    void deleteAllCheckpointsForSources(SourceType sourceType, List<String> sourceKeys);

    /**
     * Atomically sets ALL RUNNING checkpoints for a scan to PAUSED.
     * Business purpose: Race-condition-safe pause — uses a single UPDATE statement
     * so no in-flight scan event can overwrite the PAUSED status between read and write.
     *
     * @param scanId the business identifier of the scan
     * @return the number of checkpoints updated
     */
    int pauseAllRunningCheckpoints(String scanId);

    /**
     * Atomically sets ALL PAUSED checkpoints for a scan to RUNNING.
     * Business purpose: On resume, update checkpoint status BEFORE the scan emits new events,
     * so the UPSERT guard (which blocks PAUSED → RUNNING from scan events) does not reject them.
     *
     * @param scanId the business identifier of the scan
     * @return the number of checkpoints updated
     */
    int resumeAllPausedCheckpoints(String scanId);

    /**
     * Finds the running scan checkpoint for a given scan.
     *
     * @param scanId the business identifier of the scan
     * @return the running checkpoint if present, otherwise empty
     */
    Optional<ScanCheckpoint> findRunningScanCheckpoint(String scanId);

    /**
     * Deletes all checkpoints (regardless of status) for a given source type.
     * Business purpose: Full purge before starting a fresh scan.
     *
     * @param sourceType the type of the datasource to purge
     */
    void deleteAllBySourceType(SourceType sourceType);

    /**
     * Deletes all checkpoints (regardless of status) for specific sources of a given type.
     * Business purpose: Full purge before starting a fresh selected scan.
     *
     * @param sourceType the type of the datasource
     * @param sourceKeys list of source keys to purge
     */
    void deleteAllBySourceTypeAndSourceKeys(SourceType sourceType, List<String> sourceKeys);
}
