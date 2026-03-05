package pro.softcom.aisentinel.domain.jira;

import java.time.Instant;

public record JiraComment(
    String id,
    String authorDisplayName,
    String bodyText,
    Instant created,
    Instant updated
) {}
