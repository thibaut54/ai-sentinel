package pro.softcom.aisentinel.infrastructure.confluence.adapter.out.http;

import pro.softcom.aisentinel.domain.confluence.ConfluenceDeploymentType;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds URLs for the Confluence REST API.
 * Owns all API path constants and deployment-type-specific expand logic.
 */
public class ConfluenceApiUrlBuilder {


    private static final String REST_API_PREFIX = "rest/api";
    @SuppressWarnings("java:S1075") // Fixed Confluence content path — not a configurable parameter
    // NOSONAR
    private static final String CONTENT_PATH = "/content/";
    private static final String SEARCH_CONTENT_PATH = "/content/search";
    private static final String SPACE_PATH = "/space";
    private static final String ATTACHMENT_CHILD_SUFFIX = "/child/attachment";
    private static final String DEFAULT_PAGE_EXPANDS = "body.storage,version,metadata,ancestors";
    private static final String DEFAULT_SPACE_EXPANDS = "metadata";
    private static final String EXPAND_PARAM = "?expand=";

    private final ConfluenceConnectionConfig config;

    public ConfluenceApiUrlBuilder(ConfluenceConnectionConfig config) {
        this.config = config;
    }

    public URI buildPageUri(String pageId) {
        return URI.create(getRestApiUrl() + CONTENT_PATH + pageId + EXPAND_PARAM + DEFAULT_PAGE_EXPANDS);
    }

    public URI buildSpacePagesUri(String spaceKey, int startIndex, int pageSize) {
        return URI.create(
            String.format("%s%s/%s/content?expand=version,body.storage&limit=%d&start=%d",
                getRestApiUrl(), SPACE_PATH, spaceKey, pageSize, startIndex));
    }

    public URI buildSearchUri(String cql) {
        var encodedCql = URLEncoder.encode(cql, StandardCharsets.UTF_8);
        return URI.create(
            getRestApiUrl() + SEARCH_CONTENT_PATH + "?cql=" + encodedCql + "&expand=body.storage,version");
    }

    public URI buildSpaceUri(String spaceKeyOrId) {
        return URI.create(
            getRestApiUrl() + SPACE_PATH + "/" + spaceKeyOrId + EXPAND_PARAM + DEFAULT_SPACE_EXPANDS);
    }

    public URI buildUpdatePageUri(String pageId) {
        return URI.create(getRestApiUrl() + CONTENT_PATH + pageId);
    }

    public URI buildAllSpacesUri(int startIndex, int pageSize) {
        return URI.create(
            getRestApiUrl() + SPACE_PATH + EXPAND_PARAM + DEFAULT_SPACE_EXPANDS
                + "&limit=" + pageSize + "&start=" + startIndex);
    }

    public URI buildConnectionTestUri() {
        return URI.create(getRestApiUrl() + SPACE_PATH);
    }

    /**
     * Builds URI for retrieving a space with permissions.
     * Confluence Data Center does not support expand=permissions on /rest/api/space
     * (CONFSERVER-78176), so permissions are only added for Cloud.
     */
    public URI buildSpaceUriWithPermissions(String spaceKey) {
        String expandParam;
        if (config.deploymentType() == ConfluenceDeploymentType.DATA_CENTER) {
            expandParam = DEFAULT_SPACE_EXPANDS;
        } else {
            expandParam = DEFAULT_SPACE_EXPANDS + ",permissions";
        }
        return URI.create(
            getRestApiUrl() + SPACE_PATH + "/" + spaceKey + EXPAND_PARAM + expandParam);
    }

    public URI buildContentSearchModifiedSinceUri(String spaceKey, String sinceDate) {
        var cql = String.format("lastModified>=\"%s\" AND space=\"%s\"", sinceDate, spaceKey);
        var encodedCql = URLEncoder.encode(cql, StandardCharsets.UTF_8);
        return URI.create(
            getRestApiUrl() + SEARCH_CONTENT_PATH + "?cql=" + encodedCql
                + "&expand=version,history.lastUpdated");
    }

    public URI buildAttachmentListUri(String pageId) {
        return URI.create(
            getRestApiUrl() + CONTENT_PATH + pageId + ATTACHMENT_CHILD_SUFFIX
                + "?limit=200&expand=results._links");
    }

    public URI buildAttachmentListWithMetadataUri(String pageId) {
        return URI.create(
            getRestApiUrl() + CONTENT_PATH + pageId + ATTACHMENT_CHILD_SUFFIX
                + "?limit=200&expand=results._links,results.metadata");
    }

    private String getRestApiUrl() {
        var base = config.baseUrl();
        return base.endsWith("/") ? base + REST_API_PREFIX : base + "/" + REST_API_PREFIX;
    }
}
