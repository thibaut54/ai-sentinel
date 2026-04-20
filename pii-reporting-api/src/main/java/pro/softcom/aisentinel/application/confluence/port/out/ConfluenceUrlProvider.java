package pro.softcom.aisentinel.application.confluence.port.out;

/**
 * Application-level provider for building Confluence URLs.
 * Keeps the application layer agnostic of infrastructure configuration classes.
 */
public interface ConfluenceUrlProvider {
    String baseUrl();

    /**
     * Build a public page URL for the given Confluence page identifier.
     *
     * <p>The {@code spaceKey} is required for Confluence Cloud deployments, whose canonical
     * page URL format is {@code {base}/spaces/{spaceKey}/pages/{pageId}}. Data Center
     * deployments rely on the legacy {@code {base}/pages/viewpage.action?pageId={pageId}}
     * format, where the space key is not needed and is therefore ignored.
     *
     * @param spaceKey Confluence space key (mandatory for Cloud, ignored for Data Center)
     * @param pageId   Confluence page identifier
     * @return the public page URL, or {@code null} when a required input is blank
     *         (baseUrl, pageId, or spaceKey for Cloud deployments)
     */
    String pageUrl(String spaceKey, String pageId);

    /**
     * Build a URL that lists attachments visible from a user-friendly navigation context.
     *
     * <p>Cloud and Data Center expose attachments at different granularities:
     * <ul>
     *     <li>Cloud: {@code {base}/spaces/listattachmentsforspace.action?key={spaceKey}} —
     *     space-level listing; the space key is URL-encoded and the page id is ignored.</li>
     *     <li>Data Center: {@code {base}/pages/viewpageattachments.action?pageId={pageId}} —
     *     page-level listing; the space key is ignored.</li>
     * </ul>
     *
     * <p>This asymmetry reflects a deliberate product choice and is not normalized.
     *
     * @param spaceKey Confluence space key (mandatory for Cloud, ignored for Data Center)
     * @param pageId   Confluence page identifier (mandatory for Data Center, ignored for Cloud)
     * @return the attachments listing URL, or {@code null} when a required input is blank
     *         (baseUrl, spaceKey for Cloud, or pageId for Data Center)
     */
    String attachmentsUrl(String spaceKey, String pageId);
}
