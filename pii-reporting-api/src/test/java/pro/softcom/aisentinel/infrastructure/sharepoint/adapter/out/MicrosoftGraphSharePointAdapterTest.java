package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out;

import com.microsoft.graph.drives.DrivesRequestBuilder;
import com.microsoft.graph.drives.item.DriveItemRequestBuilder;
import com.microsoft.graph.drives.item.items.ItemsRequestBuilder;
import com.microsoft.graph.drives.item.items.item.DriveItemItemRequestBuilder;
import com.microsoft.graph.drives.item.items.item.children.ChildrenRequestBuilder;
import com.microsoft.graph.drives.item.items.item.content.ContentRequestBuilder;
import com.microsoft.graph.models.*;
import com.microsoft.graph.models.odataerrors.MainError;
import com.microsoft.graph.models.odataerrors.ODataError;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.sites.SitesRequestBuilder;
import com.microsoft.graph.sites.item.SiteItemRequestBuilder;
import com.microsoft.graph.sites.item.drive.DriveRequestBuilder;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Verifies the adapter correctly translates Microsoft Graph SDK responses
 * into domain objects and handles error cases gracefully.
 */
@ExtendWith(MockitoExtension.class)
class MicrosoftGraphSharePointAdapterTest {

    @Mock
    private GraphServiceClient graphServiceClient;

    private MicrosoftGraphSharePointAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MicrosoftGraphSharePointAdapter(() -> graphServiceClient);
    }

    // ============================
    // testConnection
    // ============================

    @Test
    void Should_ReturnTrue_When_RootSiteIsAccessible() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);
        var site = new Site();
        site.setId("root-site-id");

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("root")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.get()).thenReturn(site);

        // Act
        Boolean result = adapter.testConnection().get();

        // Assert
        assertThat(result).isTrue();
    }

    @Test
    void Should_ReturnFalse_When_RootSiteReturnsNull() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("root")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.get()).thenReturn(null);

        // Act
        Boolean result = adapter.testConnection().get();

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    void Should_ReturnFalse_When_ODataErrorOccursDuringConnectionTest() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);
        var oDataError = createODataError(401, "Unauthorized", "Invalid credentials");

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("root")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.get()).thenThrow(oDataError);

        // Act
        Boolean result = adapter.testConnection().get();

        // Assert
        assertThat(result).isFalse();
    }

    @Test
    void Should_ReturnFalse_When_UnexpectedExceptionDuringConnectionTest() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("root")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.get()).thenThrow(new RuntimeException("Network error"));

        // Act
        Boolean result = adapter.testConnection().get();

        // Assert
        assertThat(result).isFalse();
    }

    // ============================
    // getSite
    // ============================

    @Test
    void Should_ReturnSharePointSite_When_SiteExistsInGraph() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);
        var site = createGraphSite("site-123", "Engineering", "https://sp.com/eng", "Engineering team site");

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("site-123")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.get()).thenReturn(site);

        // Act
        SharePointSite result = adapter.getSite("site-123").get();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).isNotNull();
        softly.assertThat(result.id()).isEqualTo("site-123");
        softly.assertThat(result.name()).isEqualTo("Engineering");
        softly.assertThat(result.webUrl()).isEqualTo("https://sp.com/eng");
        softly.assertThat(result.description()).isEqualTo("Engineering team site");
        softly.assertAll();
    }

    @Test
    void Should_ReturnSiteWithUnknownName_When_DisplayNameIsNull() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);
        var site = createGraphSite("site-456", null, "https://sp.com/noname", "No name");

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("site-456")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.get()).thenReturn(site);

        // Act
        SharePointSite result = adapter.getSite("site-456").get();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).isNotNull();
        softly.assertThat(result.name()).isEqualTo("Unknown");
        softly.assertAll();
    }

    @Test
    void Should_ReturnNull_When_SiteNotFoundInGraph() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("unknown")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.get()).thenReturn(null);

        // Act
        SharePointSite result = adapter.getSite("unknown").get();

        // Assert
        assertThat(result).isNull();
    }

    @Test
    void Should_ReturnNull_When_ExceptionOccursDuringGetSite() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("error-site")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.get()).thenThrow(new RuntimeException("API timeout"));

        // Act
        SharePointSite result = adapter.getSite("error-site").get();

        // Assert
        assertThat(result).isNull();
    }

    // ============================
    // searchSites
    // ============================

    @Test
    void Should_ReturnMappedSites_When_SearchReturnsResults() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteCollectionResponse = new SiteCollectionResponse();
        siteCollectionResponse.setValue(List.of(
            createGraphSite("s1", "HR Site", "https://sp.com/hr", "Human Resources"),
            createGraphSite("s2", "IT Site", "https://sp.com/it", "IT Department")
        ));

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.get(any())).thenReturn(siteCollectionResponse);

        // Act
        List<SharePointSite> result = adapter.searchSites("HR").get();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(2);
        softly.assertThat(result.get(0).id()).isEqualTo("s1");
        softly.assertThat(result.get(0).name()).isEqualTo("HR Site");
        softly.assertThat(result.get(1).id()).isEqualTo("s2");
        softly.assertThat(result.get(1).name()).isEqualTo("IT Site");
        softly.assertAll();
    }

    @Test
    void Should_ReturnEmptyList_When_SearchReturnsNullResponse() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.get(any())).thenReturn(null);

        // Act
        List<SharePointSite> result = adapter.searchSites("query").get();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_SearchReturnsNullValue() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteCollectionResponse = new SiteCollectionResponse();
        siteCollectionResponse.setValue(null);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.get(any())).thenReturn(siteCollectionResponse);

        // Act
        List<SharePointSite> result = adapter.searchSites("query").get();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_SearchThrowsException() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.get(any())).thenThrow(new RuntimeException("Search failed"));

        // Act
        List<SharePointSite> result = adapter.searchSites("failing-query").get();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_UseEmptyQuery_When_SearchCalledWithWildcard() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteCollectionResponse = new SiteCollectionResponse();
        siteCollectionResponse.setValue(List.of(
            createGraphSite("s1", "Any Site", "https://sp.com/any", null)
        ));

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.get(any())).thenReturn(siteCollectionResponse);

        // Act
        List<SharePointSite> result = adapter.searchSites("*").get();

        // Assert
        assertThat(result).hasSize(1);
    }

    // ============================
    // listRootDriveItems
    // ============================

    @Test
    void Should_ReturnDriveItems_When_SiteHasDefaultDriveWithItems() throws Exception {
        // Arrange
        setupDriveChain("site-1", "drive-abc", createDriveItemCollectionResponse());

        // Act
        List<SharePointDriveItem> result = adapter.listRootDriveItems("site-1").get();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(2);
        softly.assertThat(result.get(0).id()).isEqualTo("item-1");
        softly.assertThat(result.get(0).name()).isEqualTo("report.pdf");
        softly.assertThat(result.get(0).isFolder()).isFalse();
        softly.assertThat(result.get(0).mimeType()).isEqualTo("application/pdf");
        softly.assertThat(result.get(0).driveId()).isEqualTo("drive-abc");
        softly.assertThat(result.get(1).id()).isEqualTo("item-2");
        softly.assertThat(result.get(1).name()).isEqualTo("Documents");
        softly.assertThat(result.get(1).isFolder()).isTrue();
        softly.assertThat(result.get(1).mimeType()).isNull();
        softly.assertAll();
    }

    @Test
    void Should_ReturnEmptyList_When_SiteHasNoDrive() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);
        var driveRequestBuilder = mock(DriveRequestBuilder.class);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("no-drive-site")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.drive()).thenReturn(driveRequestBuilder);
        when(driveRequestBuilder.get()).thenReturn(null);

        // Act
        List<SharePointDriveItem> result = adapter.listRootDriveItems("no-drive-site").get();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_DriveHasNullId() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);
        var driveRequestBuilder = mock(DriveRequestBuilder.class);
        var drive = new Drive();
        drive.setId(null);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("null-id-site")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.drive()).thenReturn(driveRequestBuilder);
        when(driveRequestBuilder.get()).thenReturn(drive);

        // Act
        List<SharePointDriveItem> result = adapter.listRootDriveItems("null-id-site").get();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_RootChildrenResponseIsNull() throws Exception {
        // Arrange
        setupDriveChain("site-null-resp", "drive-1", null);

        // Act
        List<SharePointDriveItem> result = adapter.listRootDriveItems("site-null-resp").get();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_ListRootDriveItemsThrowsException() throws Exception {
        // Arrange
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);
        var driveRequestBuilder = mock(DriveRequestBuilder.class);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId("error-site")).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.drive()).thenReturn(driveRequestBuilder);
        when(driveRequestBuilder.get()).thenThrow(new RuntimeException("Drive API error"));

        // Act
        List<SharePointDriveItem> result = adapter.listRootDriveItems("error-site").get();

        // Assert
        assertThat(result).isEmpty();
    }

    // ============================
    // listChildren
    // ============================

    @Test
    void Should_ReturnChildren_When_FolderHasChildItems() throws Exception {
        // Arrange
        var childItem = createGraphDriveItem("child-1", "readme.md", "https://sp.com/readme.md",
            "text/markdown", 512L, false);
        var response = new DriveItemCollectionResponse();
        response.setValue(List.of(childItem));

        setupChildrenChain("drive-1", "folder-1", response);

        // Act
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "folder-1").get();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(1);
        softly.assertThat(result.get(0).id()).isEqualTo("child-1");
        softly.assertThat(result.get(0).name()).isEqualTo("readme.md");
        softly.assertThat(result.get(0).driveId()).isEqualTo("drive-1");
        softly.assertAll();
    }

    @Test
    void Should_ReturnEmptyList_When_ChildrenResponseIsNull() throws Exception {
        // Arrange
        setupChildrenChain("drive-1", "empty-folder", null);

        // Act
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "empty-folder").get();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_ChildrenResponseValueIsNull() throws Exception {
        // Arrange
        var response = new DriveItemCollectionResponse();
        response.setValue(null);

        setupChildrenChain("drive-1", "null-value-folder", response);

        // Act
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "null-value-folder").get();

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_ListChildrenThrowsException() throws Exception {
        // Arrange
        var drivesRequestBuilder = mock(DrivesRequestBuilder.class);
        var driveItemRequestBuilder = mock(DriveItemRequestBuilder.class);
        var itemsRequestBuilder = mock(ItemsRequestBuilder.class);
        var driveItemItemRequestBuilder = mock(DriveItemItemRequestBuilder.class);
        var childrenRequestBuilder = mock(ChildrenRequestBuilder.class);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId("drive-1")).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId("error-folder")).thenReturn(driveItemItemRequestBuilder);
        when(driveItemItemRequestBuilder.children()).thenReturn(childrenRequestBuilder);
        when(childrenRequestBuilder.get()).thenThrow(new RuntimeException("Children API error"));

        // Act
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "error-folder").get();

        // Assert
        assertThat(result).isEmpty();
    }

    // ============================
    // downloadContent
    // ============================

    @Test
    void Should_ReturnInputStream_When_ContentDownloadSucceeds() throws Exception {
        // Arrange
        InputStream expectedContent = new ByteArrayInputStream("PDF content here".getBytes());
        setupContentChain("drive-1", "item-1", expectedContent);

        // Act
        InputStream result = adapter.downloadContent("drive-1", "item-1").get();

        // Assert
        assertThat(result).isNotNull();
        assertThat(new String(result.readAllBytes())).isEqualTo("PDF content here");
    }

    @Test
    void Should_ReturnNull_When_ContentDownloadThrowsException() throws Exception {
        // Arrange
        var drivesRequestBuilder = mock(DrivesRequestBuilder.class);
        var driveItemRequestBuilder = mock(DriveItemRequestBuilder.class);
        var itemsRequestBuilder = mock(ItemsRequestBuilder.class);
        var driveItemItemRequestBuilder = mock(DriveItemItemRequestBuilder.class);
        var contentRequestBuilder = mock(ContentRequestBuilder.class);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId("drive-1")).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId("error-item")).thenReturn(driveItemItemRequestBuilder);
        when(driveItemItemRequestBuilder.content()).thenReturn(contentRequestBuilder);
        when(contentRequestBuilder.get()).thenThrow(new RuntimeException("Download failed"));

        // Act
        InputStream result = adapter.downloadContent("drive-1", "error-item").get();

        // Assert
        assertThat(result).isNull();
    }

    // ============================
    // Mapping edge cases
    // ============================

    @Test
    void Should_MapFolderCorrectly_When_DriveItemIsFolder() throws Exception {
        // Arrange
        var folderItem = createGraphDriveItem("folder-1", "Documents", "https://sp.com/docs",
            null, null, true);
        var response = new DriveItemCollectionResponse();
        response.setValue(List.of(folderItem));

        setupChildrenChain("drive-1", "parent-1", response);

        // Act
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "parent-1").get();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(1);
        softly.assertThat(result.get(0).isFolder()).isTrue();
        softly.assertThat(result.get(0).mimeType()).isNull();
        softly.assertAll();
    }

    @Test
    void Should_MapDriveItemWithUnknownName_When_NameIsNull() throws Exception {
        // Arrange
        var item = new DriveItem();
        item.setId("item-null-name");
        item.setName(null);
        item.setWebUrl("https://sp.com/noname");
        item.setSize(100L);

        var response = new DriveItemCollectionResponse();
        response.setValue(List.of(item));

        setupChildrenChain("drive-1", "parent", response);

        // Act
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "parent").get();

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Unknown");
    }

    @Test
    void Should_MapNullMimeType_When_FileHasNoMimeType() throws Exception {
        // Arrange
        var item = new DriveItem();
        item.setId("item-no-mime");
        item.setName("mystery-file");
        item.setSize(42L);
        var fileObject = new File();
        fileObject.setMimeType(null);
        item.setFile(fileObject);

        var response = new DriveItemCollectionResponse();
        response.setValue(List.of(item));

        setupChildrenChain("drive-1", "parent", response);

        // Act
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "parent").get();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(1);
        softly.assertThat(result.get(0).mimeType()).isNull();
        softly.assertThat(result.get(0).isFolder()).isFalse();
        softly.assertAll();
    }

    @Test
    void Should_MapNullLastModified_When_TimestampIsNull() throws Exception {
        // Arrange
        var item = createGraphDriveItem("item-no-date", "old-file.txt", "https://sp.com/old",
            "text/plain", 200L, false);
        item.setLastModifiedDateTime(null);

        var response = new DriveItemCollectionResponse();
        response.setValue(List.of(item));

        setupChildrenChain("drive-1", "parent", response);

        // Act
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "parent").get();

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).lastModified()).isNull();
    }

    @Test
    void Should_MapNullMimeType_When_FileIsNull() throws Exception {
        // Arrange: a non-folder item without a File object
        var item = new DriveItem();
        item.setId("item-no-file-obj");
        item.setName("raw-item");
        item.setSize(10L);
        // file is null, folder is null => not a folder, no mime type

        var response = new DriveItemCollectionResponse();
        response.setValue(List.of(item));

        setupChildrenChain("drive-1", "parent", response);

        // Act
        List<SharePointDriveItem> result = adapter.listChildren("drive-1", "parent").get();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(1);
        softly.assertThat(result.get(0).mimeType()).isNull();
        softly.assertThat(result.get(0).isFolder()).isFalse();
        softly.assertAll();
    }

    // ============================
    // Helper methods
    // ============================

    private Site createGraphSite(String id, String displayName, String webUrl, String description) {
        var site = new Site();
        site.setId(id);
        site.setDisplayName(displayName);
        site.setWebUrl(webUrl);
        site.setDescription(description);
        return site;
    }

    private DriveItem createGraphDriveItem(String id, String name, String webUrl,
                                           String mimeType, Long size, boolean isFolder) {
        var item = new DriveItem();
        item.setId(id);
        item.setName(name);
        item.setWebUrl(webUrl);
        item.setSize(size);
        item.setLastModifiedDateTime(OffsetDateTime.now());

        if (isFolder) {
            item.setFolder(new Folder());
        } else if (mimeType != null) {
            var file = new File();
            file.setMimeType(mimeType);
            item.setFile(file);
        }
        return item;
    }

    private ODataError createODataError(int statusCode, String code, String message) {
        var mainError = new MainError();
        mainError.setCode(code);
        mainError.setMessage(message);

        var oDataError = new ODataError() {
            { setResponseStatusCode(statusCode); }
        };
        oDataError.setError(mainError);
        return oDataError;
    }

    private DriveItemCollectionResponse createDriveItemCollectionResponse() {
        var file = createGraphDriveItem("item-1", "report.pdf", "https://sp.com/report.pdf",
            "application/pdf", 4096L, false);
        var folder = createGraphDriveItem("item-2", "Documents", "https://sp.com/docs",
            null, null, true);

        var response = new DriveItemCollectionResponse();
        response.setValue(List.of(file, folder));
        return response;
    }

    private void setupDriveChain(String siteId, String driveId,
                                 DriveItemCollectionResponse driveItemsResponse) {
        var sitesRequestBuilder = mock(SitesRequestBuilder.class);
        var siteItemRequestBuilder = mock(SiteItemRequestBuilder.class);
        var driveRequestBuilder = mock(DriveRequestBuilder.class);
        var drivesRequestBuilder = mock(DrivesRequestBuilder.class);
        var driveItemRequestBuilder = mock(DriveItemRequestBuilder.class);
        var itemsRequestBuilder = mock(ItemsRequestBuilder.class);
        var driveItemItemRequestBuilder = mock(DriveItemItemRequestBuilder.class);
        var childrenRequestBuilder = mock(ChildrenRequestBuilder.class);

        var drive = new Drive();
        drive.setId(driveId);

        when(graphServiceClient.sites()).thenReturn(sitesRequestBuilder);
        when(sitesRequestBuilder.bySiteId(siteId)).thenReturn(siteItemRequestBuilder);
        when(siteItemRequestBuilder.drive()).thenReturn(driveRequestBuilder);
        when(driveRequestBuilder.get()).thenReturn(drive);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId(driveId)).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId("root")).thenReturn(driveItemItemRequestBuilder);
        when(driveItemItemRequestBuilder.children()).thenReturn(childrenRequestBuilder);
        when(childrenRequestBuilder.get()).thenReturn(driveItemsResponse);
    }

    private void setupChildrenChain(String driveId, String itemId,
                                    DriveItemCollectionResponse response) {
        var drivesRequestBuilder = mock(DrivesRequestBuilder.class);
        var driveItemRequestBuilder = mock(DriveItemRequestBuilder.class);
        var itemsRequestBuilder = mock(ItemsRequestBuilder.class);
        var driveItemItemRequestBuilder = mock(DriveItemItemRequestBuilder.class);
        var childrenRequestBuilder = mock(ChildrenRequestBuilder.class);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId(driveId)).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId(itemId)).thenReturn(driveItemItemRequestBuilder);
        when(driveItemItemRequestBuilder.children()).thenReturn(childrenRequestBuilder);
        when(childrenRequestBuilder.get()).thenReturn(response);
    }

    private void setupContentChain(String driveId, String itemId, InputStream content) {
        var drivesRequestBuilder = mock(DrivesRequestBuilder.class);
        var driveItemRequestBuilder = mock(DriveItemRequestBuilder.class);
        var itemsRequestBuilder = mock(ItemsRequestBuilder.class);
        var driveItemItemRequestBuilder = mock(DriveItemItemRequestBuilder.class);
        var contentRequestBuilder = mock(ContentRequestBuilder.class);

        when(graphServiceClient.drives()).thenReturn(drivesRequestBuilder);
        when(drivesRequestBuilder.byDriveId(driveId)).thenReturn(driveItemRequestBuilder);
        when(driveItemRequestBuilder.items()).thenReturn(itemsRequestBuilder);
        when(itemsRequestBuilder.byDriveItemId(itemId)).thenReturn(driveItemItemRequestBuilder);
        when(driveItemItemRequestBuilder.content()).thenReturn(contentRequestBuilder);
        when(contentRequestBuilder.get()).thenReturn(content);
    }
}
