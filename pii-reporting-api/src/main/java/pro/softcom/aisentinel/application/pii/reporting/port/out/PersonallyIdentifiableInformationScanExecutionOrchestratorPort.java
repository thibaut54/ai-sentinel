package pro.softcom.aisentinel.application.pii.reporting.port.out;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.ScanNotFoundException;
import reactor.core.publisher.Flux;

/**
 * Orchestrates the execution of a scan process.
 * <p>
 * Decouples the scan request from the scan execution, allowing the controller to return
 * immediately while the scan runs in the background. The controller then subscribes to
 * the scan stream via {@link #subscribeScan(String)}.
 * </p>
 */
public interface PersonallyIdentifiableInformationScanExecutionOrchestratorPort {

    /**
     * Starts a scan process in the background.
     * The scan stream is hot and shared via a sink.
     *
     * @param scanId The unique identifier of the scan
     * @param scanDataStream The cold stream of scan events
     */
    void startScan(String scanId, Flux<ContentScanResult> scanDataStream);

    /**
     * Subscribes to an existing scan to receive its events.
     *
     * <p>Allows multiple clients to subscribe to the same scan simultaneously.
     * Past events can be replayed thanks to the replay buffer (up to 1000 events).</p>
     *
     * @param scanId the scan identifier (non-null)
     * @return a Flux of scan events
     * @throws IllegalArgumentException if scanId is null
     * @throws ScanNotFoundException
     *         if the scan does not exist or has been cleaned up
     */
    Flux<ContentScanResult> subscribeScan(String scanId);

    /**
     * Checks if a scan is currently active.
     *
     * @param scanId The unique identifier of the scan
     * @return true if the scan is active, false otherwise
     */
    boolean isScanActive(String scanId);

    boolean pauseScan(String scanId);
}
