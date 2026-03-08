package pro.softcom.aisentinel.application.sharepoint.port.out;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for {@link SharePointClient} port interface.
 * Verifies that the interface contract is correctly defined and that
 * mock implementations can fulfill the expected behavior.
 */
@ExtendWith(MockitoExtension.class)
class SharePointClientTest {

    @Mock
    private SharePointClient sharePointClient;

    // --- Interface contract verification ---

    @Test
    void Should_DeclareAllExpectedMethods_When_InterfaceIsInspected() {
        Method[] methods = SharePointClient.class.getDeclaredMethods();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(methods).hasSizeGreaterThanOrEqualTo(6);

        List<String> methodNames = List.of(
            "testConnection", "getSite", "searchSites",
            "listRootDriveItems", "listChildren", "downloadContent"
        );
        for (String methodName : methodNames) {
            softly.assertThat(methods)
                .as("Interface should declare method: %s", methodName)
                .anyMatch(method -> method.getName().equals(methodName));
        }
        softly.assertAll();
    }

    @Test
    void Should_BeAnInterface_When_ClassTypeIsChecked() {
        assertThat(SharePointClient.class.isInterface()).isTrue();
    }

    // --- testConnection ---

    @Test
    void Should_ReturnTrue_When_TestConnectionSucceeds() throws Exception {
        // Arrange
        when(sharePointClient.testConnection())
            .thenReturn(CompletableFuture.completedFuture(true));

        // Act
        Boolean result = sharePointClient.testConnection().get();

        // Assert
        assertThat(result).isTrue();
        verify(sharePointClient).testConnection();
    }

    @Test
    void Should_ReturnFalse_When_TestConnectionFails() throws Exception {
        // Arrange
        when(sharePointClient.testConnection())
            .thenReturn(CompletableFuture.completedFuture(false));

        // Act
        Boolean result = sharePointClient.testConnection().get();

        // Assert
        assertThat(result).isFalse();
    }

    // --- getSite ---

    @Test
    void Should_ReturnSite_When_GetSiteCalledWithValidId() throws Exception {
        // Arrange
        var expectedSite = new SharePointSite("site-1", "Test Site", "https://sp.com/test", "A test site");
        when(sharePointClient.getSite("site-1"))
            .thenReturn(CompletableFuture.completedFuture(expectedSite));

        // Act
        SharePointSite result = sharePointClient.getSite("site-1").get();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).isNotNull();
        softly.assertThat(result.id()).isEqualTo("site-1");
        softly.assertThat(result.name()).isEqualTo("Test Site");
        softly.assertThat(result.webUrl()).isEqualTo("https://sp.com/test");
        softly.assertThat(result.description()).isEqualTo("A test site");
        softly.assertAll();
        verify(sharePointClient).getSite("site-1");
    }

    @Test
    void Should_ReturnNull_When_GetSiteCalledWithUnknownId() throws Exception {
        // Arrange
        when(sharePointClient.getSite("unknown"))
            .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        SharePointSite result = sharePointClient.getSite("unknown").get();

        // Assert
        assertThat(result).isNull();
    }

    // --- searchSites ---

    @Test
    void Should_ReturnSites_When_SearchSitesCalledWithQuery() throws Exception {
        // Arrange
        var sites = List.of(
            new SharePointSite("s1", "HR Site", "https://sp.com/hr", "HR")
        );
        when(sharePointClient.searchSites("HR"))
            .thenReturn(CompletableFuture.completedFuture(sites));

        // Act
        List<SharePointSite> result = sharePointClient.searchSites("HR").get();

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("HR Site");
        verify(sharePointClient).searchSites("HR");
    }

    @Test
    void Should_ReturnEmptyList_When_SearchSitesFindsNothing() throws Exception {
        // Arrange
        when(sharePointClient.searchSites("nonexistent"))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        // Act
        List<SharePointSite> result = sharePointClient.searchSites("nonexistent").get();

        // Assert
        assertThat(result).isEmpty();
    }

    // --- listRootDriveItems ---

    @Test
    void Should_ReturnDriveItems_When_ListRootDriveItemsCalledWithValidSiteId() throws Exception {
        // Arrange
        var items = List.of(
            new SharePointDriveItem("item-1", "doc.pdf", "url", "drive-1",
                "application/pdf", 2048L, Instant.now(), false)
        );
        when(sharePointClient.listRootDriveItems("site-1"))
            .thenReturn(CompletableFuture.completedFuture(items));

        // Act
        List<SharePointDriveItem> result = sharePointClient.listRootDriveItems("site-1").get();

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("doc.pdf");
        verify(sharePointClient).listRootDriveItems("site-1");
    }

    @Test
    void Should_ReturnEmptyList_When_ListRootDriveItemsForEmptySite() throws Exception {
        // Arrange
        when(sharePointClient.listRootDriveItems("empty-site"))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        // Act
        List<SharePointDriveItem> result = sharePointClient.listRootDriveItems("empty-site").get();

        // Assert
        assertThat(result).isEmpty();
    }

    // --- listChildren ---

    @Test
    void Should_ReturnChildren_When_ListChildrenCalledWithValidIds() throws Exception {
        // Arrange
        var children = List.of(
            new SharePointDriveItem("child-1", "subfolder", "url", "drive-1",
                null, null, null, true)
        );
        when(sharePointClient.listChildren("drive-1", "folder-1"))
            .thenReturn(CompletableFuture.completedFuture(children));

        // Act
        List<SharePointDriveItem> result = sharePointClient.listChildren("drive-1", "folder-1").get();

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).isFolder()).isTrue();
        verify(sharePointClient).listChildren("drive-1", "folder-1");
    }

    // --- downloadContent ---

    @Test
    void Should_ReturnInputStream_When_DownloadContentSucceeds() throws Exception {
        // Arrange
        InputStream content = new ByteArrayInputStream("file data".getBytes());
        when(sharePointClient.downloadContent("drive-1", "item-1"))
            .thenReturn(CompletableFuture.completedFuture(content));

        // Act
        InputStream result = sharePointClient.downloadContent("drive-1", "item-1").get();

        // Assert
        assertThat(result).isNotNull();
        assertThat(new String(result.readAllBytes())).isEqualTo("file data");
        verify(sharePointClient).downloadContent("drive-1", "item-1");
    }

    @Test
    void Should_ReturnNull_When_DownloadContentForUnknownItem() throws Exception {
        // Arrange
        when(sharePointClient.downloadContent("drive-1", "unknown-item"))
            .thenReturn(CompletableFuture.completedFuture(null));

        // Act
        InputStream result = sharePointClient.downloadContent("drive-1", "unknown-item").get();

        // Assert
        assertThat(result).isNull();
    }
}
