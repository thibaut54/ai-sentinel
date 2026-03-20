package pro.softcom.aisentinel.domain.sharepoint;

import java.time.Instant;

/**
 * Represents a file or folder in a SharePoint document library.
 *
 * @param id unique item identifier
 * @param name file or folder name
 * @param webUrl URL to access the item in a browser
 * @param driveId identifier of the parent drive
 * @param mimeType MIME type of the file (null for folders)
 * @param size file size in bytes
 * @param lastModified last modification timestamp
 * @param isFolder true if this item is a folder
 */
public record SharePointDriveItem(
    String id,
    String name,
    String webUrl,
    String driveId,
    String mimeType,
    Long size,
    Instant lastModified,
    boolean isFolder
) {
    public SharePointDriveItem {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("DriveItem id cannot be empty");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("DriveItem name cannot be empty");
        }
    }
}
