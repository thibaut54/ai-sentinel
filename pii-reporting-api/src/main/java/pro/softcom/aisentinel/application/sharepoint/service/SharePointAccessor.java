package pro.softcom.aisentinel.application.sharepoint.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointClient;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Encapsulates SharePoint data access operations with cache-first strategy.
 * Provides a unified interface for retrieving SharePoint sites and files,
 * caching sites to improve stream startup performance.
 */
@RequiredArgsConstructor
@Slf4j
public class SharePointAccessor {

    private final SharePointClient sharePointClient;
    private final List<SharePointSite> cachedSites = new CopyOnWriteArrayList<>();

    /**
     * Retrieves all sites using cache-first strategy.
     */
    public CompletableFuture<List<SharePointSite>> getAllSites() {
        log.debug("Fetching SharePoint sites with cache-first strategy");

        if (!cachedSites.isEmpty()) {
            log.debug("Returning {} cached sites", cachedSites.size());
            return CompletableFuture.completedFuture(List.copyOf(cachedSites));
        }

        synchronized (cachedSites) {
            if (!cachedSites.isEmpty()) {
                return CompletableFuture.completedFuture(List.copyOf(cachedSites));
            }

            log.debug("Cache miss - fetching sites from SharePoint API");
            return sharePointClient.searchSites("*")
                    .thenApply(sites -> {
                        if (sites != null && !sites.isEmpty()) {
                            synchronized (cachedSites) {
                                cachedSites.clear();
                                cachedSites.addAll(sites);
                            }
                            log.info("Cached {} sites from SharePoint API", sites.size());
                        }
                        return sites;
                    });
        }
    }

    /**
     * Retrieves a single site by ID.
     */
    public CompletableFuture<SharePointSite> getSite(String siteId) {
        return sharePointClient.getSite(siteId);
    }

    /**
     * Recursively collects all files (non-folder items) from all drives in a site
     * (Shared Documents, SitePages, etc.).
     */
    public CompletableFuture<List<SharePointDriveItem>> getAllFilesInSite(String siteId) {
        return sharePointClient.listAllDrivesRootItems(siteId)
                .thenCompose(rootItems -> collectFilesRecursively(rootItems, new CopyOnWriteArrayList<>()));
    }

    /**
     * Downloads file content as InputStream.
     */
    public CompletableFuture<InputStream> downloadContent(String driveId, String itemId) {
        return sharePointClient.downloadContent(driveId, itemId);
    }

    /**
     * Clears the site cache, forcing a fresh fetch on next access.
     */
    public void clearCache() {
        cachedSites.clear();
        log.debug("SharePoint site cache cleared");
    }

    private CompletableFuture<List<SharePointDriveItem>> collectFilesRecursively(
            List<SharePointDriveItem> items, List<SharePointDriveItem> accumulator) {

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

        for (SharePointDriveItem item : items) {
            if (!item.isFolder()) {
                accumulator.add(item);
            } else {
                chain = chain.thenCompose(ignored ->
                        sharePointClient.listChildren(item.driveId(), item.id())
                                .thenCompose(children -> collectFilesRecursively(children, accumulator))
                                .thenApply(ignored2 -> null));
            }
        }

        return chain.thenApply(ignored -> accumulator);
    }
}
