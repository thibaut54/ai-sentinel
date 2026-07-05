package pro.softcom.aisentinel.application.pii.reporting.port.in;

import pro.softcom.aisentinel.domain.pii.reporting.ScanSpaceStats;

import java.util.Optional;

/**
 * Application in port to read the aggregated scan statistics of a space.
 *
 * <p>Business purpose: powers the dashboard space tooltip showing the latest
 * scan's volume, failures and per-detector throughput for a given space.
 */
public interface GetScanSpaceStatsPort {

    /**
     * Resolves the latest scan of a space and returns its aggregated stats.
     *
     * @param spaceKey Confluence space key
     * @return the latest-scan stats for the space, or empty when the space has
     *         no recorded stats (e.g. scanned before this feature existed)
     */
    Optional<ScanSpaceStats> getLatestSpaceStats(String spaceKey);
}
