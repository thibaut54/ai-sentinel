package pro.softcom.aisentinel.domain.confluence;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Value object representing a validated Confluence base URL.
 * Enforces HTTPS scheme and rejects private/loopback addresses to prevent SSRF attacks.
 *
 * @param value the validated and normalized base URL (without trailing slash)
 */
public record ConfluenceBaseUrl(String value) {

    public ConfluenceBaseUrl {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Confluence base URL must not be null or blank");
        }

        URI uri = parseUri(value);
        requireHttpsScheme(uri);
        rejectPrivateHost(uri);
        value = removeTrailingSlash(uri.toString());
    }

    private static URI parseUri(String url) {
        try {
            var uri = URI.create(url.strip());
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("Confluence base URL is malformed: no host found");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Confluence base URL")) {
                throw e;
            }
            throw new IllegalArgumentException("Confluence base URL is malformed: " + url, e);
        }
    }

    private static void requireHttpsScheme(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Confluence base URL must use HTTPS scheme, got: " + uri.getScheme());
        }
    }

    private static void rejectPrivateHost(URI uri) {
        String host = uri.getHost();

        // Strip IPv6 brackets if present
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }

        if ("localhost".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("Confluence base URL must not point to a private or loopback address");
        }

        try {
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress()) {
                throw new IllegalArgumentException(
                        "Confluence base URL must not point to a private or loopback address: " + host);
            }
        } catch (UnknownHostException _) {
            // If we can't resolve, allow it — DNS resolution may succeed at runtime
            // The important check is the structural IP-based validation above
        }
    }

    private static String removeTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
