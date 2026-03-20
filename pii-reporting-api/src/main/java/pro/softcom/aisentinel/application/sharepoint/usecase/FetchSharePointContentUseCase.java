package pro.softcom.aisentinel.application.sharepoint.usecase;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.sharepoint.port.in.SharePointScanPort;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointClient;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Use case for browsing and fetching SharePoint content.
 * Delegates to the SharePointClient out-port for actual data retrieval.
 */
@RequiredArgsConstructor
@Slf4j
public class FetchSharePointContentUseCase implements SharePointScanPort {

    private final SharePointClient sharePointClient;

    @Override
    public CompletableFuture<Boolean> testConnection() {
        return sharePointClient.testConnection();
    }

    @Override
    public CompletableFuture<List<SharePointSite>> searchSites(String query) {
        log.info("Searching SharePoint sites with query: {}", query);
        return sharePointClient.searchSites(query);
    }

    @Override
    public CompletableFuture<List<SharePointDriveItem>> listDriveItems(String siteId) {
        log.info("Listing root drive items for site: {}", siteId);
        return sharePointClient.listRootDriveItems(siteId);
    }

    @Override
    public CompletableFuture<List<SharePointDriveItem>> listDriveItemChildren(String driveId, String itemId) {
        log.info("Listing children for drive={}, item={}", driveId, itemId);
        return sharePointClient.listChildren(driveId, itemId);
    }

    @Override
    public CompletableFuture<InputStream> downloadFileContent(String driveId, String itemId) {
        log.info("Downloading file content for drive={}, item={}", driveId, itemId);
        return sharePointClient.downloadContent(driveId, itemId);
    }
}
