package pro.softcom.aisentinel.domain.jira;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JiraBaseUrl")
class JiraBaseUrlTest {

    @Test
    @DisplayName("Should create valid URL when HTTPS public domain")
    void Should_CreateValidUrl_When_HttpsPublicDomain() {
        // Given
        String url = "https://mycompany.atlassian.net";

        // When
        var baseUrl = new JiraBaseUrl(url);

        // Then
        assertThat(baseUrl.value()).isEqualTo("https://mycompany.atlassian.net");
    }

    @Test
    @DisplayName("Should reject URL when scheme is HTTP")
    void Should_RejectUrl_When_SchemeIsHttp() {
        assertThatThrownBy(() -> new JiraBaseUrl("http://mycompany.atlassian.net"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @DisplayName("Should reject URL when host is loopback 127.x.x.x")
    void Should_RejectUrl_When_HostIsLoopback127() {
        assertThatThrownBy(() -> new JiraBaseUrl("https://127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    @Test
    @DisplayName("Should reject URL when host is localhost")
    void Should_RejectUrl_When_HostIsLocalhost() {
        assertThatThrownBy(() -> new JiraBaseUrl("https://localhost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    @Test
    @DisplayName("Should reject URL when host is in 10.x.x.x private network")
    void Should_RejectUrl_When_HostIsPrivate10Network() {
        assertThatThrownBy(() -> new JiraBaseUrl("https://10.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    @Test
    @DisplayName("Should reject URL when host is in 172.16-31.x.x private network")
    void Should_RejectUrl_When_HostIsPrivate172Network() {
        assertThatThrownBy(() -> new JiraBaseUrl("https://172.16.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    @Test
    @DisplayName("Should reject URL when host is in 192.168.x.x private network")
    void Should_RejectUrl_When_HostIsPrivate192Network() {
        assertThatThrownBy(() -> new JiraBaseUrl("https://192.168.1.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    @Test
    @DisplayName("Should reject URL when host is IPv6 loopback")
    void Should_RejectUrl_When_HostIsIpv6Loopback() {
        assertThatThrownBy(() -> new JiraBaseUrl("https://[::1]"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("Should reject URL when blank")
    void Should_RejectUrl_When_UrlIsBlank(String url) {
        assertThatThrownBy(() -> new JiraBaseUrl(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should reject URL when null")
    void Should_RejectUrl_When_UrlIsNull() {
        assertThatThrownBy(() -> new JiraBaseUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should normalize trailing slash")
    void Should_NormalizeTrailingSlash() {
        // Given
        var withSlash = new JiraBaseUrl("https://mycompany.atlassian.net/");
        var withoutSlash = new JiraBaseUrl("https://mycompany.atlassian.net");

        // Then
        assertThat(withSlash.value()).isEqualTo(withoutSlash.value());
        assertThat(withSlash.value()).doesNotEndWith("/");
    }

    @Test
    @DisplayName("Should reject URL when malformed")
    void Should_RejectUrl_When_Malformed() {
        assertThatThrownBy(() -> new JiraBaseUrl("not-a-url"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should accept URL when host is non-private 172 address")
    void Should_AcceptUrl_When_HostIsNonPrivate172Address() {
        // 172.32.0.1 is outside the 172.16-31.x.x private range
        var baseUrl = new JiraBaseUrl("https://172.32.0.1");
        assertThat(baseUrl.value()).isEqualTo("https://172.32.0.1");
    }

    @Test
    @DisplayName("Should reject URL when host is 0.0.0.0")
    void Should_RejectUrl_When_HostIsZeroAddress() {
        assertThatThrownBy(() -> new JiraBaseUrl("https://0.0.0.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }

    @Test
    @DisplayName("Should reject URL when host is 169.254.x.x link-local")
    void Should_RejectUrl_When_HostIsLinkLocal() {
        assertThatThrownBy(() -> new JiraBaseUrl("https://169.254.1.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");
    }
}
