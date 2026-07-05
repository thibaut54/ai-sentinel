package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.http;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.domain.confluence.ConfluenceDeploymentType;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ConfluenceApiUrlBuilder.
 * Verifies URL construction for Confluence REST API endpoints.
 */
@ExtendWith(MockitoExtension.class)
class ConfluenceApiUrlBuilderTest {

    @Mock
    private ConfluenceConnectionConfig config;

    private ConfluenceApiUrlBuilder urlBuilder;

    @BeforeEach
    void setUp() {
        lenient().when(config.baseUrl()).thenReturn("https://confluence.test.com");

        urlBuilder = new ConfluenceApiUrlBuilder(config);
    }

    @Test
    void Should_BuildPageUri_When_ValidInputs() {
        URI uri = urlBuilder.buildPageUri("12345");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri).isNotNull();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/content/12345?expand=body.storage,version,metadata,ancestors");
        softly.assertAll();
    }

    @Test
    void Should_BuildSpacePagesUri_When_PaginationProvided() {
        URI uri = urlBuilder.buildSpacePagesUri("SPACE", 10, 50);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/space/SPACE/content?expand=version,body.storage&limit=50&start=10");
        softly.assertAll();
    }

    @Test
    void Should_BuildSearchUri_When_CqlContainsSpecialCharacters() {
        var cql = "title ~ \"My Page: v1\" AND space = \"SPACE 1\"";
        var expectedEncoded = URLEncoder.encode(cql, StandardCharsets.UTF_8);

        URI uri = urlBuilder.buildSearchUri(cql);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString()).startsWith("https://confluence.test.com/rest/api/content/search?cql=");
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/content/search?cql=" + expectedEncoded + "&expand=body.storage,version");
        softly.assertAll();
    }

    @Test
    void Should_BuildSpaceUri_When_KeyProvided() {
        URI uri = urlBuilder.buildSpaceUri("SPACE");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/space/SPACE?expand=metadata");
        softly.assertAll();
    }

    @Test
    void Should_BuildUpdatePageUri_When_PageIdGiven() {
        URI uri = urlBuilder.buildUpdatePageUri("999");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/content/999");
        softly.assertAll();
    }

    @Test
    void Should_BuildAllSpacesUri_When_PaginationProvided() {
        URI uri = urlBuilder.buildAllSpacesUri(0, 25);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/space?expand=metadata&limit=25&start=0");
        softly.assertAll();
    }

    @Test
    void Should_BuildConnectionTestUri_When_Called() {
        URI uri = urlBuilder.buildConnectionTestUri();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/space");
        softly.assertAll();
    }

    @Test
    void Should_BuildContentSearchModifiedSinceUri_When_SpaceAndDateProvided() {
        var spaceKey = "SPACE Key";
        var sinceDate = "2024-12-31T23:59:59Z";

        var producedUri = urlBuilder.buildContentSearchModifiedSinceUri(spaceKey, sinceDate);

        var builderCql = String.format("lastModified>=\"%s\" AND space=\"%s\"", sinceDate, spaceKey);
        var builderEncoded = URLEncoder.encode(builderCql, StandardCharsets.UTF_8);
        var expectedUri = "https://confluence.test.com/rest/api/content/search?cql=" + builderEncoded + "&expand=version,history.lastUpdated";

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(producedUri.toString()).isEqualTo(expectedUri);
        softly.assertAll();
    }

    @Test
    void Should_BuildAttachmentListUri_When_PageIdProvided() {
        URI uri = urlBuilder.buildAttachmentListUri("42");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/content/42/child/attachment?limit=200&expand=results._links");
        softly.assertAll();
    }

    @Test
    void Should_BuildAttachmentListWithMetadataUri_When_PageIdProvided() {
        URI uri = urlBuilder.buildAttachmentListWithMetadataUri("42");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/content/42/child/attachment?limit=200&expand=results._links,results.metadata");
        softly.assertAll();
    }

    // --- Data Center specific tests ---

    @Test
    void Should_BuildAllSpacesUriWithoutPermissions_When_DataCenter() {
        URI uri = urlBuilder.buildAllSpacesUri(0, 250);

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/space?expand=metadata&limit=250&start=0");
        softly.assertThat(uri.toString()).doesNotContain("permissions");
        softly.assertAll();
    }

    @Test
    void Should_BuildSpaceUriWithoutPermissions_When_DataCenter() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);

        URI uri = urlBuilder.buildSpaceUriWithPermissions("SPACE");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/space/SPACE?expand=metadata");
        softly.assertThat(uri.toString()).doesNotContain("permissions");
        softly.assertAll();
    }

    @Test
    void Should_BuildSpaceUriWithPermissions_When_Cloud() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);

        URI uri = urlBuilder.buildSpaceUriWithPermissions("SPACE");

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(uri.toString())
            .isEqualTo("https://confluence.test.com/rest/api/space/SPACE?expand=metadata,permissions");
        softly.assertAll();
    }
}
