package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.in.dto;

/**
 * A page or attachment that failed during a scan, exposed in the tooltip.
 *
 * @param itemType type of the failed item (PAGE or ATTACHMENT)
 * @param title    human-readable title (page title or attachment name)
 */
public record FailedScanItemDto(
    String itemType,
    String title
) { }
