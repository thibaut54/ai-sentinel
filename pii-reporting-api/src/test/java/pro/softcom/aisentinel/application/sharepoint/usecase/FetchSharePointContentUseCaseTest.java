package pro.softcom.aisentinel.application.sharepoint.usecase;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FetchSharePointContentUseCase}.
 */
@ExtendWith(MockitoExtension.class)
class FetchSharePointContentUseCaseTest {

    @Mock
    private SharePointClient sharePointClient;

    @InjectMocks
    private FetchSharePointContentUseCase useCase;

    @Test
    void Should_ReturnTrue_When_ConnectionSucceeds() throws Exception {
        // Given
        when(sharePointClient.testConnection())
            .thenReturn(CompletableFuture.completedFuture(true));

        // When
        Boolean result = useCase.testConnection().get();

        // Then
        assertThat(result).isTrue();
        verify(sharePointClient).testConnection();
    }

    @Test
    void Should_ReturnFalse_When_ConnectionFails() throws Exception {
        // Given
        when(sharePointClient.testConnection())
            .thenReturn(CompletableFuture.completedFuture(false));

        // When
        Boolean result = useCase.testConnection().get();

        // Then
        assertThat(result).isFalse();
    }

    @Test
    void Should_ReturnSites_When_SearchSitesWithQuery() throws Exception {
        // Given
        var sites = List.of(
            new SharePointSite("site-1", "HR Site", "https://sp.com/hr", "Human Resources"),
            new SharePointSite("site-2", "IT Site", "https://sp.com/it", "IT Department")
        );
        when(sharePointClient.searchSites("HR"))
            .thenReturn(CompletableFuture.completedFuture(sites));

        // When
        List<SharePointSite> result = useCase.searchSites("HR").get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(2);
        softly.assertThat(result.get(0).name()).isEqualTo("HR Site");
        softly.assertThat(result.get(1).name()).isEqualTo("IT Site");
        softly.assertAll();
        verify(sharePointClient).searchSites("HR");
    }

    @Test
    void Should_ReturnDriveItems_When_ListDriveItemsForSite() throws Exception {
        // Given
        var items = List.of(
            new SharePointDriveItem("item-1", "report.pdf", "url1", "drive-1",
                "application/pdf", 1024L, Instant.now(), false),
            new SharePointDriveItem("item-2", "Documents", "url2", "drive-1",
                null, null, null, true)
        );
        when(sharePointClient.listRootDriveItems("site-123"))
            .thenReturn(CompletableFuture.completedFuture(items));

        // When
        List<SharePointDriveItem> result = useCase.listDriveItems("site-123").get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).hasSize(2);
        softly.assertThat(result.get(0).name()).isEqualTo("report.pdf");
        softly.assertThat(result.get(0).isFolder()).isFalse();
        softly.assertThat(result.get(1).name()).isEqualTo("Documents");
        softly.assertThat(result.get(1).isFolder()).isTrue();
        softly.assertAll();
        verify(sharePointClient).listRootDriveItems("site-123");
    }

    @Test
    void Should_ReturnChildren_When_ListDriveItemChildren() throws Exception {
        // Given
        var children = List.of(
            new SharePointDriveItem("child-1", "notes.txt", "url", "drive-1",
                "text/plain", 256L, Instant.now(), false)
        );
        when(sharePointClient.listChildren("drive-1", "folder-1"))
            .thenReturn(CompletableFuture.completedFuture(children));

        // When
        List<SharePointDriveItem> result = useCase.listDriveItemChildren("drive-1", "folder-1").get();

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("notes.txt");
        verify(sharePointClient).listChildren("drive-1", "folder-1");
    }

    @Test
    void Should_ReturnInputStream_When_DownloadFileContent() throws Exception {
        // Given
        InputStream content = new ByteArrayInputStream("file content".getBytes());
        when(sharePointClient.downloadContent("drive-1", "item-1"))
            .thenReturn(CompletableFuture.completedFuture(content));

        // When
        InputStream result = useCase.downloadFileContent("drive-1", "item-1").get();

        // Then
        assertThat(result).isNotNull();
        assertThat(new String(result.readAllBytes())).isEqualTo("file content");
        verify(sharePointClient).downloadContent("drive-1", "item-1");
    }

    @Test
    void Should_ReturnEmptyList_When_NoSitesFound() throws Exception {
        // Given
        when(sharePointClient.searchSites("nonexistent"))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        // When
        List<SharePointSite> result = useCase.searchSites("nonexistent").get();

        // Then
        assertThat(result).isEmpty();
    }
}
