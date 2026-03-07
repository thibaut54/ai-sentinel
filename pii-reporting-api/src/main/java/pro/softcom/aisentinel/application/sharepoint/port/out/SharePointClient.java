package pro.softcom.aisentinel.application.sharepoint.port.out;

import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Outbound port for SharePoint data access via Microsoft Graph API.
 * Infrastructure adapters must implement this port.
 */
public interface SharePointClient {

    CompletableFuture<Boolean> testConnection();

    CompletableFuture<List<SharePointSite>> searchSites(String query);

    CompletableFuture<List<SharePointDriveItem>> listRootDriveItems(String siteId);

    CompletableFuture<List<SharePointDriveItem>> listChildren(String driveId, String itemId);

    CompletableFuture<InputStream> downloadContent(String driveId, String itemId);
}
