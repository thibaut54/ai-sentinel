package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.confluence.ConfluenceDeploymentType;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConfluenceUrlProviderAdapter}.
 *
 * <p>Covers:
 * <ul>
 *     <li>Cloud deployment with standard and personal space keys (expects {@code /spaces/{spaceKey}/pages/{pageId}})</li>
 *     <li>Data Center deployment (expects the legacy {@code /pages/viewpage.action?pageId=} format, preserved verbatim)</li>
 *     <li>Graceful degradation on blank or null inputs</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ConfluenceUrlProviderAdapterTest {

    private static final String CLOUD_BASE_URL = "https://softcomtechnologies.atlassian.net/wiki";
    private static final String DC_BASE_URL = "https://confluence.example.com";
    private static final String PAGE_ID = "3757932546";
    private static final String STANDARD_SPACE_KEY = "TEAM";
    private static final String PERSONAL_SPACE_KEY = "~712020ca89d83794544f019984a297a88b7d27";

    @Mock
    private ConfluenceConnectionConfig confluenceConnectionConfig;

    private ConfluenceUrlProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ConfluenceUrlProviderAdapter(confluenceConnectionConfig);
    }

    @Test
    @DisplayName("baseUrl returns the configured base URL verbatim")
    void Should_ReturnConfiguredBaseUrl_When_BaseUrlIsRequested() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);

        assertThat(adapter.baseUrl()).isEqualTo(CLOUD_BASE_URL);
    }

    @Test
    @DisplayName("Cloud deployment builds /spaces/{spaceKey}/pages/{pageId} with a standard space key")
    void Should_BuildCloudUrlWithSpacesSegment_When_DeploymentIsCloudAndSpaceKeyIsStandard() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.pageUrl(STANDARD_SPACE_KEY, PAGE_ID);

        assertThat(url).isEqualTo(CLOUD_BASE_URL + "/spaces/" + STANDARD_SPACE_KEY + "/pages/" + PAGE_ID);
    }

    @Test
    @DisplayName("Cloud deployment URL-encodes the space key when it is a personal space (starts with '~')")
    void Should_UrlEncodeSpaceKey_When_DeploymentIsCloudAndSpaceKeyIsPersonal() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.pageUrl(PERSONAL_SPACE_KEY, PAGE_ID);

        String expectedEncodedSpaceKey = URLEncoder.encode(PERSONAL_SPACE_KEY, StandardCharsets.UTF_8);
        assertThat(expectedEncodedSpaceKey)
            .as("The personal space key must be URL-encoded (the '~' becomes '%7E')")
            .isNotEqualTo(PERSONAL_SPACE_KEY);
        assertThat(url).isEqualTo(CLOUD_BASE_URL + "/spaces/" + expectedEncodedSpaceKey + "/pages/" + PAGE_ID);
    }

    @Test
    @DisplayName("Cloud deployment returns null when the space key is null (forces callers to provide a space key)")
    void Should_ReturnNull_When_DeploymentIsCloudAndSpaceKeyIsNull() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.pageUrl(null, PAGE_ID);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Cloud deployment returns null when the space key is blank")
    void Should_ReturnNull_When_DeploymentIsCloudAndSpaceKeyIsBlank() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.pageUrl("   ", PAGE_ID);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Cloud deployment trims a trailing slash from the base URL before appending the path")
    void Should_TrimTrailingSlash_When_DeploymentIsCloudAndBaseUrlEndsWithSlash() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL + "/");
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.pageUrl(STANDARD_SPACE_KEY, PAGE_ID);

        assertThat(url).isEqualTo(CLOUD_BASE_URL + "/spaces/" + STANDARD_SPACE_KEY + "/pages/" + PAGE_ID);
    }

    @Test
    @DisplayName("Data Center deployment keeps the legacy /pages/viewpage.action?pageId= format unchanged")
    void Should_KeepLegacyDataCenterUrl_When_DeploymentIsDataCenterAndSpaceKeyIsProvided() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(DC_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        String url = adapter.pageUrl(STANDARD_SPACE_KEY, PAGE_ID);

        assertThat(url).isEqualTo(DC_BASE_URL + "/pages/viewpage.action?pageId=" + PAGE_ID);
    }

    @Test
    @DisplayName("Data Center deployment ignores a null space key (legacy format does not need it)")
    void Should_KeepLegacyDataCenterUrl_When_DeploymentIsDataCenterAndSpaceKeyIsNull() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(DC_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        String url = adapter.pageUrl(null, PAGE_ID);

        assertThat(url).isEqualTo(DC_BASE_URL + "/pages/viewpage.action?pageId=" + PAGE_ID);
    }

    @Test
    @DisplayName("Data Center deployment ignores a blank space key (legacy format does not need it)")
    void Should_KeepLegacyDataCenterUrl_When_DeploymentIsDataCenterAndSpaceKeyIsBlank() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(DC_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        String url = adapter.pageUrl("   ", PAGE_ID);

        assertThat(url).isEqualTo(DC_BASE_URL + "/pages/viewpage.action?pageId=" + PAGE_ID);
    }

    @Test
    @DisplayName("Data Center deployment trims a trailing slash from the base URL")
    void Should_TrimTrailingSlash_When_DeploymentIsDataCenterAndBaseUrlEndsWithSlash() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(DC_BASE_URL + "/");
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        String url = adapter.pageUrl(STANDARD_SPACE_KEY, PAGE_ID);

        assertThat(url).isEqualTo(DC_BASE_URL + "/pages/viewpage.action?pageId=" + PAGE_ID);
    }

    @Test
    @DisplayName("Returns null when the page id is null regardless of deployment type")
    void Should_ReturnNull_When_PageIdIsNull() {
        lenient().when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        lenient().when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.pageUrl(STANDARD_SPACE_KEY, null);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Returns null when the page id is blank regardless of deployment type")
    void Should_ReturnNull_When_PageIdIsBlank() {
        lenient().when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        lenient().when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.pageUrl(STANDARD_SPACE_KEY, "   ");

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Returns null when the configured base URL is null")
    void Should_ReturnNull_When_BaseUrlIsNull() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(null);

        String url = adapter.pageUrl(STANDARD_SPACE_KEY, PAGE_ID);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Returns null when the configured base URL is blank")
    void Should_ReturnNull_When_BaseUrlIsBlank() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn("   ");

        String url = adapter.pageUrl(STANDARD_SPACE_KEY, PAGE_ID);

        assertThat(url).isNull();
    }

    // --- attachmentsUrl (Cloud: space-level list, Data Center: page-level list) ------------

    @Test
    @DisplayName("Cloud deployment builds /spaces/listattachmentsforspace.action?key={spaceKey} with a standard space key")
    void Should_BuildCloudAttachmentsUrlWithSpaceKey_When_DeploymentIsCloudAndSpaceKeyIsStandard() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn("https://example.atlassian.net/wiki");
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.attachmentsUrl("TEST", PAGE_ID);

        assertThat(url).isEqualTo("https://example.atlassian.net/wiki/spaces/listattachmentsforspace.action?key=TEST");
    }

    @Test
    @DisplayName("Cloud attachments URL URL-encodes the space key when it is a personal space (starts with '~')")
    void Should_UrlEncodeSpaceKey_When_AttachmentsUrlIsCloudAndSpaceKeyIsPersonal() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.attachmentsUrl(PERSONAL_SPACE_KEY, PAGE_ID);

        String expectedEncodedSpaceKey = URLEncoder.encode(PERSONAL_SPACE_KEY, StandardCharsets.UTF_8);
        assertThat(expectedEncodedSpaceKey)
            .as("The personal space key must be URL-encoded (the '~' becomes '%7E')")
            .isNotEqualTo(PERSONAL_SPACE_KEY);
        assertThat(url).isEqualTo(
            CLOUD_BASE_URL + "/spaces/listattachmentsforspace.action?key=" + expectedEncodedSpaceKey);
    }

    @Test
    @DisplayName("Cloud attachments URL returns null when the space key is blank (no space-level list can be built)")
    void Should_ReturnNull_When_DeploymentIsCloudAndSpaceKeyIsBlank_OnAttachmentsUrl() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.attachmentsUrl("   ", PAGE_ID);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Cloud attachments URL ignores the page id (space-level listing only)")
    void Should_IgnorePageId_When_DeploymentIsCloudOnAttachmentsUrl() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.attachmentsUrl(STANDARD_SPACE_KEY, null);

        assertThat(url).isEqualTo(CLOUD_BASE_URL + "/spaces/listattachmentsforspace.action?key=" + STANDARD_SPACE_KEY);
    }

    @Test
    @DisplayName("Data Center attachments URL builds /pages/viewpageattachments.action?pageId={pageId}")
    void Should_BuildDataCenterAttachmentsUrlWithPageId_When_DeploymentIsDataCenter() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn("https://example.com/wiki");
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        String url = adapter.attachmentsUrl(STANDARD_SPACE_KEY, "12345");

        assertThat(url).isEqualTo("https://example.com/wiki/pages/viewpageattachments.action?pageId=12345");
    }

    @Test
    @DisplayName("Data Center attachments URL returns null when the page id is blank")
    void Should_ReturnNull_When_DeploymentIsDataCenterAndPageIdIsBlank_OnAttachmentsUrl() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(DC_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        String url = adapter.attachmentsUrl(STANDARD_SPACE_KEY, "   ");

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Data Center attachments URL ignores the space key (page-level listing only)")
    void Should_IgnoreSpaceKey_When_DeploymentIsDataCenterOnAttachmentsUrl() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(DC_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        String url = adapter.attachmentsUrl(null, PAGE_ID);

        assertThat(url).isEqualTo(DC_BASE_URL + "/pages/viewpageattachments.action?pageId=" + PAGE_ID);
    }

    @Test
    @DisplayName("Attachments URL trims a trailing slash from the base URL on Cloud")
    void Should_TrimTrailingSlash_When_AttachmentsUrlIsCloudAndBaseUrlEndsWithSlash() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL + "/");
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.attachmentsUrl(STANDARD_SPACE_KEY, PAGE_ID);

        assertThat(url).isEqualTo(CLOUD_BASE_URL + "/spaces/listattachmentsforspace.action?key=" + STANDARD_SPACE_KEY);
    }

    @Test
    @DisplayName("Attachments URL trims a trailing slash from the base URL on Data Center")
    void Should_TrimTrailingSlash_When_AttachmentsUrlIsDataCenterAndBaseUrlEndsWithSlash() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(DC_BASE_URL + "/");
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        String url = adapter.attachmentsUrl(STANDARD_SPACE_KEY, PAGE_ID);

        assertThat(url).isEqualTo(DC_BASE_URL + "/pages/viewpageattachments.action?pageId=" + PAGE_ID);
    }

    @Test
    @DisplayName("Attachments URL returns null when the configured base URL is blank")
    void Should_ReturnNull_When_AttachmentsUrlBaseUrlIsBlank() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn("   ");

        String url = adapter.attachmentsUrl(STANDARD_SPACE_KEY, PAGE_ID);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Attachments URL returns null when the configured base URL is null")
    void Should_ReturnNull_When_AttachmentsUrlBaseUrlIsNull() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(null);

        String url = adapter.attachmentsUrl(STANDARD_SPACE_KEY, PAGE_ID);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Cloud attachments URL returns null when both space key and page id are null")
    void Should_ReturnNull_When_DeploymentIsCloudAndBothArgsAreNull_OnAttachmentsUrl() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.attachmentsUrl(null, null);

        assertThat(url).isNull();
    }

    @Test
    @DisplayName("Data Center attachments URL returns null when both space key and page id are null")
    void Should_ReturnNull_When_DeploymentIsDataCenterAndBothArgsAreNull_OnAttachmentsUrl() {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(DC_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        String url = adapter.attachmentsUrl(null, null);

        assertThat(url).isNull();
    }

    // --- Form-encoding guarantees for exotic space keys (pageUrl + attachmentsUrl) ---------
    // URLEncoder.encode uses application/x-www-form-urlencoded semantics:
    //   ' ' -> '+'    '/' -> '%2F'    '%' -> '%25'
    // These tests lock the behavior so any future refactor of urlEncode stays explicit.

    @ParameterizedTest(name = "pageUrl Cloud encodes ''{0}'' as ''{1}''")
    @CsvSource({
        "'space key', 'space+key'",
        "'a/b',       'a%2Fb'",
        "'50%',       '50%25'"
    })
    @DisplayName("pageUrl form-encodes exotic characters in the space key on Cloud")
    void Should_FormEncodeSpaceKey_When_DeploymentIsCloudOnPageUrl(String rawSpaceKey, String expectedEncoded) {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.pageUrl(rawSpaceKey, PAGE_ID);

        assertThat(url).isEqualTo(CLOUD_BASE_URL + "/spaces/" + expectedEncoded + "/pages/" + PAGE_ID);
    }

    @ParameterizedTest(name = "attachmentsUrl Cloud encodes ''{0}'' as ''{1}''")
    @CsvSource({
        "'space key', 'space+key'",
        "'a/b',       'a%2Fb'",
        "'50%',       '50%25'"
    })
    @DisplayName("attachmentsUrl form-encodes exotic characters in the space key on Cloud")
    void Should_FormEncodeSpaceKey_When_DeploymentIsCloudOnAttachmentsUrl(String rawSpaceKey, String expectedEncoded) {
        when(confluenceConnectionConfig.baseUrl()).thenReturn(CLOUD_BASE_URL);
        when(confluenceConnectionConfig.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        String url = adapter.attachmentsUrl(rawSpaceKey, PAGE_ID);

        assertThat(url).isEqualTo(CLOUD_BASE_URL + "/spaces/listattachmentsforspace.action?key=" + expectedEncoded);
    }
}
