package pro.softcom.aisentinel.domain.sharepoint;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SharePointSite} domain record.
 */
class SharePointSiteTest {

    @Test
    void Should_CreateSite_When_ValidDataProvided() {
        // When
        var site = new SharePointSite("site-123", "My Site", "https://example.sharepoint.com/sites/mysite", "A test site");

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(site.id()).isEqualTo("site-123");
        softly.assertThat(site.name()).isEqualTo("My Site");
        softly.assertThat(site.webUrl()).isEqualTo("https://example.sharepoint.com/sites/mysite");
        softly.assertThat(site.description()).isEqualTo("A test site");
        softly.assertAll();
    }

    @Test
    void Should_CreateSite_When_DescriptionIsNull() {
        // When
        var site = new SharePointSite("site-123", "My Site", "https://example.sharepoint.com", null);

        // Then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(site.id()).isEqualTo("site-123");
        softly.assertThat(site.name()).isEqualTo("My Site");
        softly.assertThat(site.description()).isNull();
        softly.assertAll();
    }

    @Test
    void Should_ThrowException_When_IdIsNull() {
        assertThatThrownBy(() -> new SharePointSite(null, "My Site", "https://example.com", "desc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Site id cannot be empty");
    }

    @Test
    void Should_ThrowException_When_IdIsBlank() {
        assertThatThrownBy(() -> new SharePointSite("  ", "My Site", "https://example.com", "desc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Site id cannot be empty");
    }

    @Test
    void Should_ThrowException_When_NameIsNull() {
        assertThatThrownBy(() -> new SharePointSite("site-123", null, "https://example.com", "desc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Site name cannot be empty");
    }

    @Test
    void Should_ThrowException_When_NameIsBlank() {
        assertThatThrownBy(() -> new SharePointSite("site-123", "  ", "https://example.com", "desc"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Site name cannot be empty");
    }
}
