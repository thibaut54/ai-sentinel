package pro.softcom.aisentinel.domain.pii.reporting;

/**
 * A page or attachment that failed during a scan.
 *
 * <p>Business purpose: surfaces the items that could not be analyzed so the
 * dashboard tooltip can list what was skipped.
 *
 * @param itemType type of the failed item (PAGE or ATTACHMENT)
 * @param title    human-readable title (page title or attachment name)
 */
public record FailedScanItem(
    ItemType itemType,
    String title
) {

    /**
     * Kind of scanned item that may fail.
     */
    public enum ItemType {
        PAGE,
        ATTACHMENT
    }
}
