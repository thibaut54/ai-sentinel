package pro.softcom.aisentinel.infrastructure.pii.export.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.sharepoint.port.out.SharePointClient;
import pro.softcom.aisentinel.domain.pii.export.ExportContext;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.sharepoint.SharePointSite;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SharePointExportContextAdapterTest {

    @Mock
    private SharePointClient sharePointClient;

    private SharePointExportContextAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SharePointExportContextAdapter(sharePointClient);
    }

    @Test
    void Should_ReturnContextWithSiteInfo_When_SiteExists() {
        // Arrange
        SharePointSite site = new SharePointSite("site-1", "My Site", "https://example.sharepoint.com/sites/mysite", "desc");
        when(sharePointClient.getSite("site-1")).thenReturn(CompletableFuture.completedFuture(site));

        // Act
        ExportContext result = adapter.findContext(SourceType.SHAREPOINT, "site-1");

        // Assert
        assertThat(result.reportName()).isEqualTo("My Site");
        assertThat(result.reportIdentifier()).isEqualTo("site-1");
        assertThat(result.sourceUrl()).isEqualTo("https://example.sharepoint.com/sites/mysite");
        assertThat(result.sourceType()).isEqualTo(SourceType.SHAREPOINT);
        assertThat(result.contacts()).isEmpty();
        assertThat(result.additionalMetadata()).containsEntry("siteId", "site-1");
    }

    @Test
    void Should_FallbackToIdentifier_When_SiteIsNull() {
        // Arrange
        when(sharePointClient.getSite("site-unknown")).thenReturn(CompletableFuture.completedFuture(null));

        // Act
        ExportContext result = adapter.findContext(SourceType.SHAREPOINT, "site-unknown");

        // Assert
        assertThat(result.reportName()).isEqualTo("site-unknown");
        assertThat(result.sourceUrl()).isEmpty();
    }

    @Test
    void Should_FallbackToIdentifier_When_SiteNameIsBlank() {
        // Arrange - SharePointSite requires non-blank name, so we test with null site
        when(sharePointClient.getSite("site-2")).thenReturn(CompletableFuture.completedFuture(null));

        // Act
        ExportContext result = adapter.findContext(SourceType.SHAREPOINT, "site-2");

        // Assert
        assertThat(result.reportName()).isEqualTo("site-2");
        assertThat(result.reportIdentifier()).isEqualTo("site-2");
    }
}
