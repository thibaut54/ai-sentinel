package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.confluence.ConfluenceDeploymentType;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfluenceUrlProviderAdapterTest {

    @Mock
    private ConfluenceConnectionConfig confluenceConnectionConfig;

    @InjectMocks
    private ConfluenceUrlProviderAdapter adapter;

    @Test
    void Should_ReturnBaseUrl_When_BaseUrlCalled() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn("https://confluence.example.com");

        String result = adapter.baseUrl();

        assertThat(result).isEqualTo("https://confluence.example.com");
    }

    @Test
    void Should_ReturnNull_When_PageIdIsNull() {
        String result = adapter.pageUrl(null);

        assertThat(result).isNull();
    }

    @Test
    void Should_ReturnNull_When_PageIdIsBlank() {
        String result = adapter.pageUrl("  ");

        assertThat(result).isNull();
    }

    @Test
    void Should_ReturnNull_When_BaseUrlIsNull() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(null);

        String result = adapter.pageUrl("12345");

        assertThat(result).isNull();
    }

    @Test
    void Should_ReturnNull_When_BaseUrlIsBlank() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn("  ");

        String result = adapter.pageUrl("12345");

        assertThat(result).isNull();
    }

    @Test
    void Should_ReturnCloudUrl_When_DeploymentTypeIsCloud() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn("https://confluence.example.com");
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String result = adapter.pageUrl("12345");

        assertThat(result).isEqualTo("https://confluence.example.com/pages/12345");
    }

    @Test
    void Should_ReturnDataCenterUrl_When_DeploymentTypeIsDataCenter() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn("https://confluence.example.com");
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        String result = adapter.pageUrl("12345");

        assertThat(result).isEqualTo("https://confluence.example.com/pages/viewpage.action?pageId=12345");
    }

    @Test
    void Should_RemoveTrailingSlash_When_BaseUrlHasTrailingSlash() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn("https://confluence.example.com/");
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String result = adapter.pageUrl("12345");

        assertThat(result)
                .isEqualTo("https://confluence.example.com/pages/12345")
                .doesNotContain("//pages");
    }
}
