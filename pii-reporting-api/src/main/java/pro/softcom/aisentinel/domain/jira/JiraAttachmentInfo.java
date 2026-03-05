package pro.softcom.aisentinel.domain.jira;

public record JiraAttachmentInfo(
    String id,
    String filename,
    String mimeType,
    long size,
    String contentUrl,
    String authorDisplayName
) {}
