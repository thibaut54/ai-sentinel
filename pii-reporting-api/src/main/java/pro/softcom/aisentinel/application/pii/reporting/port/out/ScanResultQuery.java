package pro.softcom.aisentinel.application.pii.reporting.port.out;

import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.AccessPurpose;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.reporting.LastScanMeta;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Application out port used by the reporting layer to read scan outcomes.
 */
public interface ScanResultQuery {

    /**
     * Returns the most recent scan known by the system, if any.
     *
     * @return an Optional containing the metadata of the latest scan, or empty when no scan exists
     */
    Optional<LastScanMeta> findLatestScan();

    /**
     * Returns the most recent scan for the given source type, if any.
     *
     * @param sourceType the source type to filter by (JIRA, CONFLUENCE, SHAREPOINT)
     * @return an Optional containing the metadata of the latest scan for that source type
     */
    Optional<LastScanMeta> findLatestScanBySourceType(SourceType sourceType);

    /**
     * Returns progress counters per space for the given scan.
     * The counters reflect how many pages and attachments have been processed,
     * along with the timestamp of the last observed event for that space.
     *
     * @param scanId the business identifier of the scan to inspect
     * @return a list of per-space counters for the requested scan (may be empty)
     */
    List<SpaceCounter> getSpaceCounters(String scanId);

    /**
     * Lists the item events for the given scan in emission order.
     * An item represents a content unit such as a page or an attachment.
     *
     * @param scanId the business identifier of the scan to inspect
     * @return the ordered list of item events recorded for the scan (may be empty)
     */
    List<ContentScanResult> listItemEvents(String scanId);

    /**
     * Lists item events with ENCRYPTED PII data.
     * Use when PII values don't need to be viewed (statistics, dashboards without detail).
     *
     * @param scanId scan identifier
     * @return list of scan results with encrypted PII
     */
    List<ContentScanResult> listItemEventsEncrypted(String scanId);

    /**
     * Lists item events with DECRYPTED PII data.
     * Automatically logs access for GDPR/nLPD compliance.
     *
     * @param scanId scan identifier
     * @param contentId content ID (page, issue, file, etc.)
     * @param purpose access purpose (for audit trail)
     * @return list of scan results with decrypted PII
     */
    List<ContentScanResult> listItemEventsDecrypted(String scanId, String contentId, AccessPurpose purpose);

    /**
     * Lists item events with ENCRYPTED PII data filtered by source.
     * Use when PII values don't need to be viewed.
     *
     * @param scanId scan identifier
     * @param sourceKey source key to filter results (space key, project key, site id, etc.)
     * @return list of scan results with encrypted PII for the specified source
     */
    List<ContentScanResult> listItemEventsEncryptedBySourceKey(String scanId, String sourceKey);

    /**
     * Read-side projection representing per-source progress within a scan.
     *
     * @param sourceKey the business key of the source (space key, project key, site id, etc.)
     * @param pagesDone number of pages/items processed in this source
     * @param attachmentsDone number of attachments processed in this source
     * @param lastEventTs timestamp of the last event observed for this source
     */
    record SpaceCounter(String sourceKey, long pagesDone, long attachmentsDone, Instant lastEventTs) {}
}
