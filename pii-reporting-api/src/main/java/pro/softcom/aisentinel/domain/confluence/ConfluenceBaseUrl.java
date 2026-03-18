package pro.softcom.aisentinel.domain.confluence;

import java.net.URI;

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

        if ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "0.0.0.0".equals(host)) {
            throw new IllegalArgumentException(
                    "Confluence base URL must not point to localhost or loopback address");
        }
    }

    private static String removeTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
