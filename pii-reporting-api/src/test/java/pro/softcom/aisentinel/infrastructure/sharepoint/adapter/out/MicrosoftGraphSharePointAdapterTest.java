package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out;

import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.items.item.children.ChildrenRequestBuilder;
import com.microsoft.graph.drives.item.items.item.content.ContentRequestBuilder;
import com.microsoft.graph.models.*;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.sites.SitesRequestBuilder;
import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.sites.item.SiteItemRequestBuilder;
import com.microsoft.graph.sites.item.drive.DriveRequestBuilder;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MicrosoftGraphSharePointAdapter}.
 */
@ExtendWith(MockitoExtension.class)
class MicrosoftGraphSharePointAdapterTest {

    @Mock
    private GraphServiceClient graphServiceClient;

    @InjectMocks
    private MicrosoftGraphSharePointAdapter adapter;

    @Test
    void Should_ReturnTrue_When_ConnectionTestSucceeds() throws Exception {
        // Given
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteCollectionResponse = new SiteCollectionResponse();
        siteCollectionResponse.setValue(List.of(new Site()));
        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.get(any())).thenReturn(siteCollectionResponse);

        // When
        Boolean result = adapter.testConnection().get();

        // Then
        assertThat(result).isTrue();
    }

    @Test
    void Should_ReturnFalse_When_ConnectionTestThrowsException() throws Exception {
        // Given
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.get(any())).thenThrow(new RuntimeException("Connection refused"));

        // When
        Boolean result = adapter.testConnection().get();

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void Should_ReturnSites_When_SearchSitesSucceeds() throws Exception {
        // Given
        var site = new Site();
        site.setId("site-1");
        site.setDisplayName("Test Site");
        site.setWebUrl("https://sp.com/test");
        site.setDescription("A test site");

        var siteCollectionResponse = new SiteCollectionResponse();
        siteCollectionResponse.setValue(List.of(site));

        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.get(any())).thenReturn(siteCollectionResponse);

        // When
        List<SharePointSite> result = adapter.searchSites("test").get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(1);
        softly.assertThat(result.get(0).id()).isEqualTo("site-1");
        softly.assertThat(result.get(0).name()).isEqualTo("Test Site");
        softly.assertThat(result.get(0).webUrl()).isEqualTo("https://sp.com/test");
        softly.assertThat(result.get(0).description()).isEqualTo("A test site");
        softly.assertAll();
    }

    @Test
    void Should_ReturnEmptyList_When_SearchSitesReturnsNull() throws Exception {
        // Given
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.get(any())).thenReturn(null);

        // When
        List<SharePointSite> result = adapter.searchSites("test").get();

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_SearchSitesThrowsException() throws Exception {
        // Given
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.get(any())).thenThrow(new RuntimeException("API error"));

        // When
        List<SharePointSite> result = adapter.searchSites("test").get();

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnDriveItems_When_ListRootDriveItemsSucceeds() throws Exception {
        // Given - mock the chain: sites().bySiteId().drive().get()
        var drive = new Drive();
        drive.setId("drive-1");

        var driveRequestBuilder = mock(DriveRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("site-1")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.drive()).thenReturn(driveRequestBuilder);
        when(driveRequestBuilder.get()).thenReturn(drive);

        // Mock the chain: drives().byDriveId().items().byDriveItemId("root").children().get()
        var driveItem = new DriveItem();
        driveItem.setId("item-1");
        driveItem.setName("report.pdf");
        driveItem.setWebUrl("https://sp.com/report.pdf");
        driveItem.setSize(2048L);
        driveItem.setLastModifiedDateTime(OffsetDateTime.parse("2026-01-15T10:30:00Z"));
        var fileInfo = new com.microsoft.graph.models.File();
        fileInfo.setMimeType("application/pdf");
        driveItem.setFile(fileInfo);

        var driveItemCollectionResponse = new DriveItemCollectionResponse();
        driveItemCollectionResponse.setValue(List.of(driveItem));

        var drivesRequestBuilder = mock(DrivesRequestBuilder.class);
        var driveItemRequestBuilder = mock(DriveItemRequestBuilder.class);
        var itemsRequestBuilder = mock(ItemsRequestBuilder.class);
        var driveItemItemRequestBuilder = mock(DriveItemItemRequestBuilder.class);
        var childrenRequestBuilder = mock(ChildrenRequestBuilder.class);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId("drive-1")).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId("root")).thenReturn(driveItemItemRequestBuilder);
        when(driveItemItemRequestBuilder.children()).thenReturn(childrenRequestBuilder);
        when(childrenRequestBuilder.get()).thenReturn(driveItemCollectionResponse);

        // When
        List<SharePointDriveItem> result = adapter.listRootDriveItems("site-1").get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(1);
        softly.assertThat(result.get(0).id()).isEqualTo("item-1");
        softly.assertThat(result.get(0).name()).isEqualTo("report.pdf");
        softly.assertThat(result.get(0).driveId()).isEqualTo("drive-1");
        softly.assertThat(result.get(0).mimeType()).isEqualTo("application/pdf");
        softly.assertThat(result.get(0).size()).isEqualTo(2048L);
        softly.assertThat(result.get(0).isFolder()).isFalse();
        softly.assertAll();
    }

    @Test
    void Should_ReturnEmptyList_When_NoDriveFoundForSite() throws Exception {
        // Given
        var driveRequestBuilder = mock(DriveRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("site-1")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.drive()).thenReturn(driveRequestBuilder);
        when(driveRequestBuilder.get()).thenReturn(null);

        // When
        List<SharePointDriveItem> result = adapter.listRootDriveItems("site-1").get();

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnChildren_When_ListChildrenSucceeds() throws Exception {
        // Given
        var childItem = new DriveItem();
        childItem.setId("child-1");
        childItem.setName("notes.txt");
        childItem.setWebUrl("https://sp.com/notes.txt");
        childItem.setSize(512L);
        var fileInfo = new com.microsoft.graph.models.File();
        fileInfo.setMimeType("text/plain");
        childItem.setFile(fileInfo);

        var driveItemCollectionResponse = new DriveItemCollectionResponse();
        driveItemCollectionResponse.setValue(List.of(childItem));

        var drivesRequestBuilder = mock(DrivesRequestBuilder.class);
        var driveItemRequestBuilder = mock(DriveItemRequestBuilder.class);
        var itemsRequestBuilder = mock(ItemsRequestBuilder.class);
        var driveItemItemRequestBuilder = mock(DriveItemItemRequestBuilder.class);
        var childrenRequestBuilder = mock(ChildrenRequestBuilder.class);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId("drive-1")).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId("folder-1")).thenReturn(driveItemItemRequestBuilder);
        when(driveItemItemRequestBuilder.children()).thenReturn(childrenRequestBuilder);
        when(childrenRequestBuilder.get()).thenReturn(driveItemCollectionResponse);

        // When
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "folder-1").get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(1);
        softly.assertThat(result.get(0).id()).isEqualTo("child-1");
        softly.assertThat(result.get(0).name()).isEqualTo("notes.txt");
        softly.assertThat(result.get(0).mimeType()).isEqualTo("text/plain");
        softly.assertAll();
    }

    @Test
    void Should_ReturnInputStream_When_DownloadContentSucceeds() throws Exception {
        // Given
        InputStream expectedContent = new ByteArrayInputStream("file data".getBytes());

        var drivesRequestBuilder = mock(DrivesRequestBuilder.class);
        var driveItemRequestBuilder = mock(DriveItemRequestBuilder.class);
        var itemsRequestBuilder = mock(ItemsRequestBuilder.class);
        var driveItemItemRequestBuilder = mock(DriveItemItemRequestBuilder.class);
        var contentRequestBuilder = mock(ContentRequestBuilder.class);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId("drive-1")).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId("item-1")).thenReturn(driveItemItemRequestBuilder);
        when(driveItemItemRequestBuilder.content()).thenReturn(contentRequestBuilder);
        when(contentRequestBuilder.get()).thenReturn(expectedContent);

        // When
        InputStream result = adapter.downloadContent("drive-1", "item-1").get();

        // Then
        assertThat(result).isNotNull();
        assertThat(new String(result.readAllBytes())).isEqualTo("file data");
    }

    @Test
    void Should_ReturnNull_When_DownloadContentThrowsException() throws Exception {
        // Given
        var drivesRequestBuilder = mock(DrivesRequestBuilder.class);
        var driveItemRequestBuilder = mock(DriveItemRequestBuilder.class);
        var itemsRequestBuilder = mock(ItemsRequestBuilder.class);
        var driveItemItemRequestBuilder = mock(DriveItemItemRequestBuilder.class);
        var contentRequestBuilder = mock(ContentRequestBuilder.class);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId("drive-1")).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId("item-1")).thenReturn(driveItemItemRequestBuilder);
        when(driveItemItemRequestBuilder.content()).thenReturn(contentRequestBuilder);
        when(contentRequestBuilder.get()).thenThrow(new RuntimeException("Download failed"));

        // When
        InputStream result = adapter.downloadContent("drive-1", "item-1").get();

        // Then
        assertThat(result).isNull();
    }

    @Test
    void Should_MapFolderCorrectly_When_DriveItemIsFolder() throws Exception {
        // Given
        var folderItem = new DriveItem();
        folderItem.setId("folder-1");
        folderItem.setName("Documents");
        folderItem.setWebUrl("https://sp.com/Documents");
        folderItem.setFolder(new Folder());

        var driveItemCollectionResponse = new DriveItemCollectionResponse();
        driveItemCollectionResponse.setValue(List.of(folderItem));

        var drivesRequestBuilder = mock(DrivesRequestBuilder.class);
        var driveItemRequestBuilder = mock(DriveItemRequestBuilder.class);
        var itemsRequestBuilder = mock(ItemsRequestBuilder.class);
        var driveItemItemRequestBuilder = mock(DriveItemItemRequestBuilder.class);
        var childrenRequestBuilder = mock(ChildrenRequestBuilder.class);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId("drive-1")).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId("parent-1")).thenReturn(driveItemItemRequestBuilder);
        when(driveItemItemRequestBuilder.children()).thenReturn(childrenRequestBuilder);
        when(childrenRequestBuilder.get()).thenReturn(driveItemCollectionResponse);

        // When
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "parent-1").get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(1);
        softly.assertThat(result.get(0).isFolder()).isTrue();
        softly.assertThat(result.get(0).mimeType()).isNull();
        softly.assertAll();
    }
}
