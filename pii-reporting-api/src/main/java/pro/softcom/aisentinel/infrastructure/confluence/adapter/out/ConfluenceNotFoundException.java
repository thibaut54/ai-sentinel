package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import lombok.Getter;

@Getter
public final class ConfluenceNotFoundException extends ConfluenceException {
    private final String resourceId;

    public ConfluenceNotFoundException(String resourceId, String resourceType) {
        super(String.format("%s avec l'ID '%s' non trouvé", resourceType, resourceId), 404, null);
        this.resourceId = resourceId;
    }
}
