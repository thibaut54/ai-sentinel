package pro.softcom.aisentinel.domain.sharepoint;

import lombok.Getter;

@Getter
public final class SharePointNotFoundException extends SharePointException {
    private final String resourceId;

    public SharePointNotFoundException(String resourceId, String resourceType) {
        super(String.format("%s with ID '%s' not found", resourceType, resourceId), 404, null);
        this.resourceId = resourceId;
    }
}
