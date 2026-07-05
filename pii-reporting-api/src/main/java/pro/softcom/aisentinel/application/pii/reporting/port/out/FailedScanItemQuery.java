package pro.softcom.aisentinel.application.pii.reporting.port.out;

import pro.softcom.aisentinel.domain.pii.reporting.FailedScanItem;

import java.util.List;

/**
 * Application out port to read the items that failed during a scan.
 *
 * <p>Business purpose: feeds the dashboard tooltip with a deduplicated, bounded
 * list of pages/attachments that could not be analyzed, sourced from the error
 * events recorded during the scan.
 */
public interface FailedScanItemQuery {

    /**
     * Lists the distinct failed items of a space scan, bounded for display.
     *
     * @param scanId   unique scan identifier
     * @param spaceKey Confluence space key
     * @param limit    maximum number of items to return
     * @return deduplicated failed items (may be empty)
     */
    List<FailedScanItem> findFailedItems(String scanId, String spaceKey, int limit);
}
