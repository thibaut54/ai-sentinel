package pro.softcom.aisentinel.application.sharepoint.port.in;

import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Inbound port for SharePoint operations.
 * Exposes capabilities needed by controllers to browse and scan SharePoint content.
 */
public interface SharePointScanPort {

    CompletableFuture<Boolean> testConnection();

    CompletableFuture<List<SharePointSite>> searchSites(String query);

    CompletableFuture<List<SharePointDriveItem>> listDriveItems(String siteId);

    CompletableFuture<List<SharePointDriveItem>> listDriveItemChildren(String driveId, String itemId);

    CompletableFuture<InputStream> downloadFileContent(String driveId, String itemId);
}
