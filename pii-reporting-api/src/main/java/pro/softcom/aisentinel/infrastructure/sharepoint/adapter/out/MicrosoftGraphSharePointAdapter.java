package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out;

import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.models.Site;
import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointClient;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Infrastructure adapter implementing SharePointClient using Microsoft Graph SDK v6.
 * Translates Graph API responses into domain objects.
 * Uses a Supplier to allow dynamic client replacement when DB-backed credentials change.
 */
@Component
@Slf4j
public class MicrosoftGraphSharePointAdapter implements SharePointClient {

    private final Supplier<GraphServiceClient> graphClientSupplier;

    public MicrosoftGraphSharePointAdapter(Supplier<GraphServiceClient> graphClientSupplier) {
        this.graphClientSupplier = graphClientSupplier;
    }

    private GraphServiceClient client() {
        return graphClientSupplier.get();
    }

    @Override
    public CompletableFuture<Boolean> testConnection() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var root = client().sites().bySiteId("root").get();
                return root != null;
            } catch (ODataError e) {
                log.error("[SHAREPOINT] Connection test failed: HTTP {} - code={}, message={}",
                    e.getResponseStatusCode(),
                    e.getError() != null ? e.getError().getCode() : "unknown",
                    e.getError() != null ? e.getError().getMessage() : e.getMessage(), e);
                return false;
            } catch (Exception e) {
                log.error("[SHAREPOINT] Connection test failed: {}", e.getMessage(), e);
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<SharePointSite> getSite(String siteId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var site = client().sites().bySiteId(siteId).get();
                if (site == null) {
                    return null;
                }
                return toSharePointSite(site);
            } catch (Exception e) {
                log.error("[SHAREPOINT] Error fetching site {}: {}", siteId, e.getMessage(), e);
                return null;
            }
        });
    }

    @Override
    public CompletableFuture<List<SharePointSite>> searchSites(String query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var effectiveQuery = "*".equals(query) ? "" : query;
                var result = client().sites().get(config ->
                    config.queryParameters.search = effectiveQuery
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
                var drive = client().sites().bySiteId(siteId).drive().get();
                if (drive == null || drive.getId() == null) {
                    log.warn("[SHAREPOINT] No default drive found for site {}", siteId);
                    return List.of();
                }
                // List root children
                var response = client().drives()
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
    public CompletableFuture<List<SharePointDriveItem>> listAllDrivesRootItems(String siteId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var drivesResponse = client().sites().bySiteId(siteId).drives().get();
                if (drivesResponse == null || drivesResponse.getValue() == null) {
                    return List.of();
                }
                log.debug("[SHAREPOINT] Found {} drives for site {}", drivesResponse.getValue().size(), siteId);
                drivesResponse.getValue().forEach(d ->
                    log.debug("[SHAREPOINT]   drive: name={}, id={}, driveType={}", d.getName(), d.getId(), d.getDriveType())
                );
                return drivesResponse.getValue().stream()
                    .filter(drive -> drive.getId() != null)
                    .flatMap(drive -> {
                        try {
                            var response = client().drives()
                                .byDriveId(drive.getId())
                                .items()
                                .byDriveItemId("root")
                                .children()
                                .get();
                            if (response == null || response.getValue() == null) {
                                return java.util.stream.Stream.empty();
                            }
                            return response.getValue().stream()
                                .map(item -> toDriveItem(item, drive.getId()));
                        } catch (Exception e) {
                            log.warn("[SHAREPOINT] Error listing items for drive {}: {}", drive.getId(), e.getMessage());
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .toList();
            } catch (Exception e) {
                log.error("[SHAREPOINT] Error listing all drives for site {}: {}", siteId, e.getMessage(), e);
                return List.of();
            }
        });
    }

    @Override
    public CompletableFuture<List<SharePointDriveItem>> listChildren(String driveId, String itemId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var response = client().drives()
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
                return client().drives()
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
