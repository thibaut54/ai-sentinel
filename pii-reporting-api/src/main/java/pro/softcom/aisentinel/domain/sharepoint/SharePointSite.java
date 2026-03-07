package pro.softcom.aisentinel.domain.sharepoint;

/**
 * Represents a SharePoint site (equivalent to a Confluence space).
 *
 * @param id unique site identifier from Microsoft Graph
 * @param name display name of the site
 * @param webUrl URL to access the site in a browser
 * @param description site description
 */
public record SharePointSite(
    String id,
    String name,
    String webUrl,
    String description
) {
    public SharePointSite {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Site id cannot be empty");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Site name cannot be empty");
        }
    }
}
