package pro.softcom.aisentinel.application.pii.reporting.port.out;

import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
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
     * Subscribes to an active scan stream.
     * Returns a Flux that replays recent events (buffer) and streams new ones.
     *
     * @param scanId The unique identifier of the scan
     * @return A Flux of scan events
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
