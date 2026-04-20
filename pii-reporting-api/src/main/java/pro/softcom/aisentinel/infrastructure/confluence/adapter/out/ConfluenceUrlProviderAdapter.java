package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceUrlProvider;
import pro.softcom.aisentinel.domain.confluence.ConfluenceDeploymentType;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

/**
 * Infrastructure adapter that exposes Confluence baseUrl and builds public URLs
 * through the application-level ConfluenceUrlProvider.
 */
@Component
public class ConfluenceUrlProviderAdapter implements ConfluenceUrlProvider {

    private final ConfluenceConnectionConfig confluenceConnectionConfig;

    public ConfluenceUrlProviderAdapter(@Qualifier("confluenceConfig") ConfluenceConnectionConfig confluenceConnectionConfig) {
        this.confluenceConnectionConfig = confluenceConnectionConfig;
    }

    @Override
    public String baseUrl() {
        return confluenceConnectionConfig.baseUrl();
    }

    @Override
    public String pageUrl(String spaceKey, String pageId) {
        if (isBlank(pageId)) {
            return null;
        }
        String normalizedBase = normalizedBaseUrl();
        if (normalizedBase == null) {
            return null;
        }
        if (confluenceConnectionConfig.deploymentType() == ConfluenceDeploymentType.DATA_CENTER) {
            return normalizedBase + "/pages/viewpage.action?pageId=" + pageId;
        }
        if (isBlank(spaceKey)) {
            return null;
        }
        return normalizedBase + "/spaces/" + urlEncode(spaceKey) + "/pages/" + pageId;
    }

    @Override
    public String attachmentsUrl(String spaceKey, String pageId) {
        String normalizedBase = normalizedBaseUrl();
        if (normalizedBase == null) {
            return null;
        }
        if (confluenceConnectionConfig.deploymentType() == ConfluenceDeploymentType.DATA_CENTER) {
            if (isBlank(pageId)) {
                return null;
            }
            return normalizedBase + "/pages/viewpageattachments.action?pageId=" + pageId;
        }
        if (isBlank(spaceKey)) {
            return null;
        }
        return normalizedBase + "/spaces/listattachmentsforspace.action?key=" + urlEncode(spaceKey);
    }

    private String normalizedBaseUrl() {
        String base = baseUrl();
        if (isBlank(base)) {
            return null;
        }
        base = base.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
