package pro.softcom.aisentinel.domain.jira;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JiraIssue")
class JiraIssueTest {

    @Test
    @DisplayName("Should return concatenated content body when summary, description and comments present")
    void Should_ReturnConcatenatedContentBody_When_AllFieldsPresent() {
        // Given
        var comment1 = new JiraComment("c1", "Alice", "Comment one", Instant.now(), Instant.now());
        var comment2 = new JiraComment("c2", "Bob", "Comment two", Instant.now(), Instant.now());
        var issue = JiraIssue.builder()
                .id("10001")
                .key("PROJ-1")
                .projectKey("PROJ")
                .summary("Fix login bug")
                .descriptionText("The login page crashes on submit")
                .comments(List.of(comment1, comment2))
                .build();

        // When
        String body = issue.getContentBody();

        // Then
        assertThat(body).contains("Fix login bug", "The login page crashes on submit", "Comment one", "Comment two");
    }

    @Test
    @DisplayName("Should return empty content body when all fields are null")
    void Should_ReturnEmptyContentBody_When_AllFieldsNull() {
        // Given
        var issue = JiraIssue.builder()
                .id("10001")
                .key("PROJ-1")
                .projectKey("PROJ")
                .build();

        // When
        String body = issue.getContentBody();

        // Then
        assertThat(body).isEmpty();
    }

    @Test
    @DisplayName("Should skip null comment bodies in content body")
    void Should_SkipNullCommentBodies_When_CommentsHaveNullText() {
        // Given
        var comment = new JiraComment("c1", "Alice", null, Instant.now(), Instant.now());
        var issue = JiraIssue.builder()
                .id("10001")
                .key("PROJ-1")
                .projectKey("PROJ")
                .summary("Summary")
                .comments(List.of(comment))
                .build();

        // When
        String body = issue.getContentBody();

        // Then
        assertThat(body).isEqualTo("Summary\n");
    }

    @Test
    @DisplayName("Should return formatted title with key and summary")
    void Should_ReturnFormattedTitle_When_KeyAndSummaryPresent() {
        // Given
        var issue = JiraIssue.builder()
                .id("10001")
                .key("PROJ-42")
                .projectKey("PROJ")
                .summary("Add feature X")
                .build();

        // When / Then
        assertThat(issue.getTitle()).isEqualTo("PROJ-42 - Add feature X");
    }

    @Test
    @DisplayName("Should return projectKey as sourceId")
    void Should_ReturnProjectKey_When_GetSourceIdCalled() {
        // Given
        var issue = JiraIssue.builder()
                .id("10001")
                .key("PROJ-1")
                .projectKey("PROJ")
                .build();

        // When / Then
        assertThat(issue.getSourceId()).isEqualTo("PROJ");
    }

    @Test
    @DisplayName("Should return metadata map when metadata is present")
    void Should_ReturnMetadataMap_When_MetadataPresent() {
        // Given
        var metadata = new JiraIssue.IssueMetadata(
                "reporter@example.com", "assignee@example.com", "In Progress", "Bug",
                Instant.now(), Instant.now());
        var issue = JiraIssue.builder()
                .id("10001")
                .key("PROJ-1")
                .projectKey("PROJ")
                .metadata(metadata)
                .build();

        // When
        Map<String, Object> result = issue.getMetadata();

        // Then
        assertThat(result)
                .containsEntry("reporter", "reporter@example.com")
                .containsEntry("assignee", "assignee@example.com")
                .containsEntry("status", "In Progress")
                .containsEntry("issueType", "Bug");
    }

    @Test
    @DisplayName("Should return empty metadata map when metadata is null")
    void Should_ReturnEmptyMetadataMap_When_MetadataIsNull() {
        // Given
        var issue = JiraIssue.builder()
                .id("10001")
                .key("PROJ-1")
                .projectKey("PROJ")
                .build();

        // When / Then
        assertThat(issue.getMetadata()).isEmpty();
    }

    @Test
    @DisplayName("Should handle null fields in metadata gracefully")
    void Should_HandleNullFieldsInMetadata_When_MetadataFieldsAreNull() {
        // Given
        var metadata = new JiraIssue.IssueMetadata(null, null, null, null, null, null);
        var issue = JiraIssue.builder()
                .id("10001")
                .key("PROJ-1")
                .projectKey("PROJ")
                .metadata(metadata)
                .build();

        // When
        Map<String, Object> result = issue.getMetadata();

        // Then
        assertThat(result)
                .containsEntry("reporter", "")
                .containsEntry("assignee", "")
                .containsEntry("status", "")
                .containsEntry("issueType", "");
    }

    @Test
    @DisplayName("Should return id via ScannableContent getId")
    void Should_ReturnId_When_GetIdCalled() {
        // Given
        var issue = JiraIssue.builder()
                .id("99999")
                .key("PROJ-1")
                .projectKey("PROJ")
                .build();

        // When / Then
        assertThat(issue.getId()).isEqualTo("99999");
    }
}
