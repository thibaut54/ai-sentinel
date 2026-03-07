package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.Site;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointClient;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Infrastructure adapter implementing SharePointClient using Microsoft Graph SDK v6.
 * Translates Graph API responses into domain objects.
 */
@Component
@ConditionalOnProperty(prefix = "sharepoint", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class MicrosoftGraphSharePointAdapter implements SharePointClient {

    private final GraphServiceClient graphServiceClient;

    @Override
    public CompletableFuture<Boolean> testConnection() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var result = graphServiceClient.sites().get(config ->
                    config.queryParameters.search = "*"
                );
                return result != null;
            } catch (Exception e) {
                log.error("[SHAREPOINT] Connection test failed: {}", e.getMessage());
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<List<SharePointSite>> searchSites(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var result = graphServiceClient.sites().get(config ->
                    config.queryParameters.search = query
                );
                if (result == null || result.getValue() == null) {
                    return List.of();
                }
                return result.getValue().stream()
                    .map(this::toSharePointSite)
                    .toList();
            } catch (Exception e) {
                log.error("[SHAREPOINT] Error searching sites: {}", e.getMessage(), e);
                return List.of();
            }
        });
    }

    @Override
    public CompletableFuture<List<SharePointDriveItem>> listRootDriveItems(String siteId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get the default drive for the site
                var drive = graphServiceClient.sites().bySiteId(siteId).drive().get();
                if (drive == null || drive.getId() == null) {
                    log.warn("[SHAREPOINT] No default drive found for site {}", siteId);
                    return List.of();
                }
                // List root children
                var response = graphServiceClient.drives()
                    .byDriveId(drive.getId())
                    .items()
                    .byDriveItemId("root")
                    .children()
                    .get();
                if (response == null || response.getValue() == null) {
                    return List.of();
                }
                return response.getValue().stream()
                    .map(item -> toDriveItem(item, drive.getId()))
                    .toList();
            } catch (Exception e) {
                log.error("[SHAREPOINT] Error listing root items for site {}: {}", siteId, e.getMessage(), e);
                return List.of();
            }
        });
    }

    @Override
    public CompletableFuture<List<SharePointDriveItem>> listChildren(String driveId, String itemId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var response = graphServiceClient.drives()
                    .byDriveId(driveId)
                    .items()
                    .byDriveItemId(itemId)
                    .children()
                    .get();
                if (response == null || response.getValue() == null) {
                    return List.of();
                }
                return response.getValue().stream()
                    .map(item -> toDriveItem(item, driveId))
                    .toList();
            } catch (Exception e) {
                log.error("[SHAREPOINT] Error listing children for drive={}, item={}: {}",
                    driveId, itemId, e.getMessage(), e);
                return List.of();
            }
        });
    }

    @Override
    public CompletableFuture<InputStream> downloadContent(String driveId, String itemId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return graphServiceClient.drives()
                    .byDriveId(driveId)
                    .items()
                    .byDriveItemId(itemId)
                    .content()
                    .get();
            } catch (Exception e) {
                log.error("[SHAREPOINT] Error downloading content for drive={}, item={}: {}",
                    driveId, itemId, e.getMessage(), e);
                return null;
            }
        });
    }

    private SharePointSite toSharePointSite(Site site) {
        return new SharePointSite(
            site.getId(),
            site.getDisplayName() != null ? site.getDisplayName() : "Unknown",
            site.getWebUrl(),
            site.getDescription()
        );
    }

    private SharePointDriveItem toDriveItem(DriveItem item, String driveId) {
        boolean isFolder = item.getFolder() != null;
        return new SharePointDriveItem(
            item.getId(),
            item.getName() != null ? item.getName() : "Unknown",
            item.getWebUrl(),
            driveId,
            isFolder ? null : extractMimeType(item),
            item.getSize(),
            item.getLastModifiedDateTime() != null
                ? item.getLastModifiedDateTime().toInstant()
                : null,
            isFolder
        );
    }

    private String extractMimeType(DriveItem item) {
        if (item.getFile() != null && item.getFile().getMimeType() != null) {
            return item.getFile().getMimeType();
        }
        return null;
    }
}
