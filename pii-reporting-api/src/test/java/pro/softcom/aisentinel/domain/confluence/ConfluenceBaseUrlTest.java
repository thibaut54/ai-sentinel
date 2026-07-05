package pro.softcom.aisentinel.domain.confluence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ConfluenceBaseUrl")
class ConfluenceBaseUrlTest {

    @Test
    @DisplayName("Should create valid URL when HTTPS public domain")
    void Should_CreateValidUrl_When_HttpsPublicDomain() {
        // Given
        String url = "https://mycompany.atlassian.net/wiki";

        // When
        var baseUrl = new ConfluenceBaseUrl(url);

        // Then
        assertThat(baseUrl.value()).isEqualTo("https://mycompany.atlassian.net/wiki");
    }

    @Test
    @DisplayName("Should reject URL when scheme is HTTP")
    void Should_RejectUrl_When_SchemeIsHttp() {
        assertThatThrownBy(() -> new ConfluenceBaseUrl("http://mycompany.atlassian.net/wiki"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("Should reject URL when host is loopback 127.0.0.1")
    void Should_RejectUrl_When_HostIsLoopback127() {
        assertThatThrownBy(() -> new ConfluenceBaseUrl("https://127.0.0.1/wiki"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    @DisplayName("Should reject URL when host is localhost")
    void Should_RejectUrl_When_HostIsLocalhost() {
        assertThatThrownBy(() -> new ConfluenceBaseUrl("https://localhost/wiki"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    @DisplayName("Should accept URL when host is in private network")
    void Should_AcceptUrl_When_HostIsPrivateNetwork() {
        // Corporate Confluence instances often resolve to private IPs
        assertThat(new ConfluenceBaseUrl("https://10.0.0.1/wiki").value()).isEqualTo("https://10.0.0.1/wiki");
        assertThat(new ConfluenceBaseUrl("https://172.16.0.1/wiki").value()).isEqualTo("https://172.16.0.1/wiki");
        assertThat(new ConfluenceBaseUrl("https://192.168.1.1/wiki").value()).isEqualTo("https://192.168.1.1/wiki");
    }

    @Test
    @DisplayName("Should reject URL when host is IPv6 loopback")
    void Should_RejectUrl_When_HostIsIpv6Loopback() {
        assertThatThrownBy(() -> new ConfluenceBaseUrl("https://[::1]/wiki"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Should reject URL when blank")
    void Should_RejectUrl_When_UrlIsBlank(String url) {
        assertThatThrownBy(() -> new ConfluenceBaseUrl(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject URL when null")
    void Should_RejectUrl_When_UrlIsNull() {
        assertThatThrownBy(() -> new ConfluenceBaseUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should normalize trailing slash")
    void Should_NormalizeTrailingSlash() {
        // Given
        var withSlash = new ConfluenceBaseUrl("https://mycompany.atlassian.net/wiki/");
        var withoutSlash = new ConfluenceBaseUrl("https://mycompany.atlassian.net/wiki");

        // Then
        assertThat(withSlash.value())
                .isEqualTo(withoutSlash.value())
                .doesNotEndWith("/");
    }

    @Test
    @DisplayName("Should reject URL when malformed")
    void Should_RejectUrl_When_Malformed() {
        assertThatThrownBy(() -> new ConfluenceBaseUrl("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should accept URL when host is non-private 172 address")
    void Should_AcceptUrl_When_HostIsNonPrivate172Address() {
        // 172.32.0.1 is outside the 172.16-31.x.x private range
        var baseUrl = new ConfluenceBaseUrl("https://172.32.0.1/wiki");
        assertThat(baseUrl.value()).isEqualTo("https://172.32.0.1/wiki");
    }

    @Test
    @DisplayName("Should reject URL when host is 0.0.0.0")
    void Should_RejectUrl_When_HostIsZeroAddress() {
        assertThatThrownBy(() -> new ConfluenceBaseUrl("https://0.0.0.0/wiki"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    @DisplayName("Should accept URL when host is link-local address")
    void Should_AcceptUrl_When_HostIsLinkLocal() {
        assertThat(new ConfluenceBaseUrl("https://169.254.1.1/wiki").value())
                .isEqualTo("https://169.254.1.1/wiki");
    }
}
