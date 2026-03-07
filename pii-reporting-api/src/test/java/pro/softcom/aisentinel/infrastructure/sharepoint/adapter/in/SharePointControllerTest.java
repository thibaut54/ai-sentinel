package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import pro.softcom.aisentinel.application.sharepoint.port.in.SharePointScanPort;
import pro.softcom.aisentinel.domain.sharepoint.SharePointDriveItem;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.SharePointController.SharePointDriveItemDto;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.SharePointController.SharePointHealthCheckResponse;
import pro.softcom.aisentinel.infrastructure.sharepoint.adapter.in.SharePointController.SharePointSiteDto;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SharePointController}.
 */
@ExtendWith(MockitoExtension.class)
class SharePointControllerTest {

    @Mock
    private SharePointScanPort sharePointScanPort;

    @InjectMocks
    private SharePointController controller;

    @Test
    void Should_ReturnOkWithUpStatus_When_ConnectionSucceeds() throws Exception {
        // Given
        when(sharePointScanPort.testConnection())
            .thenReturn(CompletableFuture.completedFuture(true));

        // When
        ResponseEntity<SharePointHealthCheckResponse> response = controller.checkHealth().get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        softly.assertThat(response.getBody()).isNotNull();
        softly.assertThat(response.getBody().status()).isEqualTo("UP");
        softly.assertThat(response.getBody().message()).isEqualTo("Connection to SharePoint established");
        softly.assertAll();
    }

    @Test
    void Should_ReturnServiceUnavailable_When_ConnectionFails() throws Exception {
        // Given
        when(sharePointScanPort.testConnection())
            .thenReturn(CompletableFuture.completedFuture(false));

        // When
        ResponseEntity<SharePointHealthCheckResponse> response = controller.checkHealth().get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        softly.assertThat(response.getBody()).isNotNull();
        softly.assertThat(response.getBody().status()).isEqualTo("DOWN");
        softly.assertAll();
    }

    @Test
    void Should_ReturnSiteDtos_When_SearchSitesSucceeds() throws Exception {
        // Given
        var sites = List.of(
            new SharePointSite("site-1", "HR Site", "https://sp.com/hr", "Human Resources")
        );
        when(sharePointScanPort.searchSites("HR"))
            .thenReturn(CompletableFuture.completedFuture(sites));

        // When
        ResponseEntity<List<SharePointSiteDto>> response = controller.searchSites("HR").get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        softly.assertThat(response.getBody()).hasSize(1);
        softly.assertThat(response.getBody().get(0).id()).isEqualTo("site-1");
        softly.assertThat(response.getBody().get(0).name()).isEqualTo("HR Site");
        softly.assertThat(response.getBody().get(0).webUrl()).isEqualTo("https://sp.com/hr");
        softly.assertThat(response.getBody().get(0).description()).isEqualTo("Human Resources");
        softly.assertAll();
    }

    @Test
    void Should_ReturnInternalServerError_When_SearchSitesFails() throws Exception {
        // Given
        when(sharePointScanPort.searchSites("*"))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("API error")));

        // When
        ResponseEntity<List<SharePointSiteDto>> response = controller.searchSites("*").get();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void Should_ReturnDriveItemDtos_When_ListRootItemsSucceeds() throws Exception {
        // Given
        var items = List.of(
            new SharePointDriveItem("item-1", "report.pdf", "https://sp.com/report.pdf",
                "drive-1", "application/pdf", 1024L, Instant.now(), false),
            new SharePointDriveItem("item-2", "Documents", "https://sp.com/Documents",
                "drive-1", null, null, null, true)
        );
        when(sharePointScanPort.listDriveItems("site-1"))
            .thenReturn(CompletableFuture.completedFuture(items));

        // When
        ResponseEntity<List<SharePointDriveItemDto>> response = controller.listRootItems("site-1").get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        softly.assertThat(response.getBody()).hasSize(2);
        softly.assertThat(response.getBody().get(0).name()).isEqualTo("report.pdf");
        softly.assertThat(response.getBody().get(0).isFolder()).isFalse();
        softly.assertThat(response.getBody().get(0).mimeType()).isEqualTo("application/pdf");
        softly.assertThat(response.getBody().get(1).name()).isEqualTo("Documents");
        softly.assertThat(response.getBody().get(1).isFolder()).isTrue();
        softly.assertAll();
    }

    @Test
    void Should_ReturnInternalServerError_When_ListRootItemsFails() throws Exception {
        // Given
        when(sharePointScanPort.listDriveItems("site-1"))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("API error")));

        // When
        ResponseEntity<List<SharePointDriveItemDto>> response = controller.listRootItems("site-1").get();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void Should_ReturnChildDtos_When_ListChildrenSucceeds() throws Exception {
        // Given
        var children = List.of(
            new SharePointDriveItem("child-1", "notes.txt", "https://sp.com/notes.txt",
                "drive-1", "text/plain", 256L, Instant.now(), false)
        );
        when(sharePointScanPort.listDriveItemChildren("drive-1", "folder-1"))
            .thenReturn(CompletableFuture.completedFuture(children));

        // When
        ResponseEntity<List<SharePointDriveItemDto>> response =
            controller.listChildren("drive-1", "folder-1").get();

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        softly.assertThat(response.getBody()).hasSize(1);
        softly.assertThat(response.getBody().get(0).id()).isEqualTo("child-1");
        softly.assertThat(response.getBody().get(0).name()).isEqualTo("notes.txt");
        softly.assertAll();
    }

    @Test
    void Should_ReturnInternalServerError_When_ListChildrenFails() throws Exception {
        // Given
        when(sharePointScanPort.listDriveItemChildren("drive-1", "folder-1"))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("API error")));

        // When
        ResponseEntity<List<SharePointDriveItemDto>> response =
            controller.listChildren("drive-1", "folder-1").get();

        // Then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
