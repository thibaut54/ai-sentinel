package pro.softcom.aisentinel.domain.jira;

import lombok.Builder;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Builder
public record JiraIssue(
    String id,
    String key,
    String projectKey,
    String summary,
    String descriptionText,
    List<JiraComment> comments,
    IssueMetadata metadata
) implements ScannableContent {

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getContentBody() {
        var sb = new StringBuilder();
        if (summary != null) sb.append(summary).append("\n");
        if (descriptionText != null) sb.append(descriptionText).append("\n");
        if (comments != null) {
            for (var comment : comments) {
                if (comment.bodyText() != null) {
                    sb.append(comment.bodyText()).append("\n");
                }
            }
        }
        return sb.toString();
    }

    @Override
    public String getTitle() {
        String safeKey = key != null ? key : "";
        String safeSummary = summary != null ? summary : "";
        return safeKey + " - " + safeSummary;
    }

    @Override
    public String getSourceId() {
        return projectKey;
    }

    @Override
    public Map<String, Object> getMetadata() {
        return metadata != null
            ? Map.of(
                "reporter", metadata.reporter() != null ? metadata.reporter() : "",
                "assignee", metadata.assignee() != null ? metadata.assignee() : "",
                "status", metadata.status() != null ? metadata.status() : "",
                "issueType", metadata.issueType() != null ? metadata.issueType() : ""
              )
            : Map.of();
    }

    public record IssueMetadata(
        String reporter,
        String assignee,
        String status,
        String issueType,
        Instant created,
        Instant updated
    ) {}
}
