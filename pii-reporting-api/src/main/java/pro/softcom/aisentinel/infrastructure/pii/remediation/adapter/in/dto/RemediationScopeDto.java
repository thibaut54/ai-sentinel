package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.in.dto;

/**
 * Scope of a remediation request: a whole space, one page (with its attachments),
 * or a single attachment.
 */
public record RemediationScopeDto(String spaceKey, String pageId, String attachmentName) {
}
