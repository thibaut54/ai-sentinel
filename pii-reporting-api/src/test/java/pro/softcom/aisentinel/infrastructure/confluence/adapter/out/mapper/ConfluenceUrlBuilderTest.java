package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.mapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConfluenceUrlBuilder")
class ConfluenceUrlBuilderTest {

    @BeforeEach
    void setUp() {
        ConfluenceUrlBuilder.setGlobalRootUrl("https://confluence.example.com");
    }

    @AfterEach
    void tearDown() {
        ConfluenceUrlBuilder.setGlobalRootUrl(null);
    }

    @Test
    @DisplayName("Should_ReturnSpaceOverviewUrl_When_ValidSpaceKeyAndRootUrl")
    void Should_ReturnSpaceOverviewUrl_When_ValidSpaceKeyAndRootUrl() {
        String url = ConfluenceUrlBuilder.spaceOverviewUrl("MYSPACE");
        assertThat(url).isEqualTo("https://confluence.example.com/spaces/MYSPACE/overview");
    }

    @Test
    @DisplayName("Should_ReturnNull_When_SpaceKeyIsNull")
    void Should_ReturnNull_When_SpaceKeyIsNull() {
        assertThat(ConfluenceUrlBuilder.spaceOverviewUrl(null)).isNull();
    }

    @Test
    @DisplayName("Should_ReturnNull_When_SpaceKeyIsBlank")
    void Should_ReturnNull_When_SpaceKeyIsBlank() {
        assertThat(ConfluenceUrlBuilder.spaceOverviewUrl("  ")).isNull();
    }

    @Test
    @DisplayName("Should_ReturnNull_When_RootUrlIsNull")
    void Should_ReturnNull_When_RootUrlIsNull() {
        ConfluenceUrlBuilder.setGlobalRootUrl(null);
        assertThat(ConfluenceUrlBuilder.spaceOverviewUrl("SPACE")).isNull();
    }

    @Test
    @DisplayName("Should_ReturnNull_When_RootUrlIsBlank")
    void Should_ReturnNull_When_RootUrlIsBlank() {
        ConfluenceUrlBuilder.setGlobalRootUrl("   ");
        assertThat(ConfluenceUrlBuilder.spaceOverviewUrl("SPACE")).isNull();
    }

    @Test
    @DisplayName("Should_StripTrailingSlash_When_RootUrlHasTrailingSlash")
    void Should_StripTrailingSlash_When_RootUrlHasTrailingSlash() {
        ConfluenceUrlBuilder.setGlobalRootUrl("https://confluence.example.com/");
        String url = ConfluenceUrlBuilder.spaceOverviewUrl("SPACE");
        assertThat(url).isEqualTo("https://confluence.example.com/spaces/SPACE/overview");
    }

    @Test
    @DisplayName("Should_UrlEncodeSpaceKey_When_SpaceKeyContainsSpecialChars")
    void Should_UrlEncodeSpaceKey_When_SpaceKeyContainsSpecialChars() {
        String url = ConfluenceUrlBuilder.spaceOverviewUrl("MY SPACE");
        assertThat(url).contains("MY+SPACE");
    }
}
