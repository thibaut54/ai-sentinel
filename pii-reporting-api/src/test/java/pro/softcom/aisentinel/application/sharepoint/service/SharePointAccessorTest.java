package pro.softcom.aisentinel.application.sharepoint.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointClient;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharePointAccessorTest {

    @Mock
    private SharePointClient sharePointClient;

    private SharePointAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = new SharePointAccessor(sharePointClient);
    }

    @Test
    void Should_FetchSitesFromApi_When_CacheIsEmpty() {
        // Arrange
        List<SharePointSite> sites = List.of(
                new SharePointSite("site-1", "Site One", "https://example.com/site1", "desc1")
        );
        when(sharePointClient.searchSites("*")).thenReturn(CompletableFuture.completedFuture(sites));

        // Act
        List<SharePointSite> result = accessor.getAllSites().join();

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo("site-1");
        verify(sharePointClient).searchSites("*");
    }

    @Test
    void Should_ReturnCachedSites_When_CacheIsPopulated() {
        // Arrange
        List<SharePointSite> sites = List.of(
                new SharePointSite("site-1", "Site One", "https://example.com/site1", "desc1")
        );
        when(sharePointClient.searchSites("*")).thenReturn(CompletableFuture.completedFuture(sites));
        accessor.getAllSites().join();

        // Act
        List<SharePointSite> result = accessor.getAllSites().join();

        // Assert
        assertThat(result).hasSize(1);
        verify(sharePointClient, times(1)).searchSites("*");
    }

    @Test
    void Should_DelegateToClient_When_GetSiteCalled() {
        // Arrange
        SharePointSite site = new SharePointSite("site-1", "Site One", "https://example.com/site1", "desc1");
        when(sharePointClient.getSite("site-1")).thenReturn(CompletableFuture.completedFuture(site));

        // Act
        SharePointSite result = accessor.getSite("site-1").join();

        // Assert
        assertThat(result.id()).isEqualTo("site-1");
        verify(sharePointClient).getSite("site-1");
    }

    @Test
    void Should_DelegateToClient_When_DownloadContentCalled() {
        // Arrange
        InputStream inputStream = new ByteArrayInputStream("content".getBytes());
        when(sharePointClient.downloadContent("drive-1", "item-1"))
                .thenReturn(CompletableFuture.completedFuture(inputStream));

        // Act
        InputStream result = accessor.downloadContent("drive-1", "item-1").join();

        // Assert
        assertThat(result).isNotNull();
        verify(sharePointClient).downloadContent("drive-1", "item-1");
    }

    @Test
    void Should_ClearCache_When_ClearCacheCalled() {
        // Arrange
        List<SharePointSite> sites = List.of(
                new SharePointSite("site-1", "Site One", "https://example.com/site1", "desc1")
        );
        when(sharePointClient.searchSites("*")).thenReturn(CompletableFuture.completedFuture(sites));
        accessor.getAllSites().join();

        // Act
        accessor.clearCache();

        // Second call should fetch from API again
        accessor.getAllSites().join();

        // Assert
        verify(sharePointClient, times(2)).searchSites("*");
    }

    @Test
    void Should_CollectFilesRecursively_When_SiteHasNestedFolders() {
        // Arrange
        SharePointDriveItem folder = new SharePointDriveItem(
                "folder-1", "docs", null, "drive-1", null, null, Instant.now(), true);
        SharePointDriveItem file1 = new SharePointDriveItem(
                "file-1", "readme.txt", null, "drive-1", "text/plain", 100L, Instant.now(), false);
        SharePointDriveItem file2 = new SharePointDriveItem(
                "file-2", "data.csv", null, "drive-1", "text/csv", 200L, Instant.now(), false);

        when(sharePointClient.listAllDrivesRootItems("site-1"))
                .thenReturn(CompletableFuture.completedFuture(List.of(folder, file1)));
        when(sharePointClient.listChildren("drive-1", "folder-1"))
                .thenReturn(CompletableFuture.completedFuture(List.of(file2)));

        // Act
        List<SharePointDriveItem> result = accessor.getAllFilesInSite("site-1").join();

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result).extracting(SharePointDriveItem::id).containsExactlyInAnyOrder("file-1", "file-2");
    }
}
