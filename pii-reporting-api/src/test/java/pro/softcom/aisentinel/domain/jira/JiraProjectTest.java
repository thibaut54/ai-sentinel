package pro.softcom.aisentinel.domain.jira;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JiraProject")
class JiraProjectTest {

    @Test
    @DisplayName("Should create project when key and name are valid")
    void Should_CreateProject_When_KeyAndNameAreValid() {
        // Given
        String key = "PROJ";
        String name = "My Project";

        // When
        var project = new JiraProject("1", key, name, "desc", "John", "https://jira.example.com", 42, Instant.now());

        // Then
        assertThat(project.key()).isEqualTo("PROJ");
        assertThat(project.name()).isEqualTo("My Project");
        assertThat(project.issueCount()).isEqualTo(42);
    }

    @Test
    @DisplayName("Should throw when key is null")
    void Should_Throw_When_KeyIsNull() {
        assertThatThrownBy(() -> new JiraProject("1", null, "name", null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project key cannot be empty");
    }

    @Test
    @DisplayName("Should throw when key is blank")
    void Should_Throw_When_KeyIsBlank() {
        assertThatThrownBy(() -> new JiraProject("1", "   ", "name", null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project key cannot be empty");
    }

    @Test
    @DisplayName("Should throw when name is null")
    void Should_Throw_When_NameIsNull() {
        assertThatThrownBy(() -> new JiraProject("1", "PROJ", null, null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project name cannot be empty");
    }

    @Test
    @DisplayName("Should throw when name is blank")
    void Should_Throw_When_NameIsBlank() {
        assertThatThrownBy(() -> new JiraProject("1", "PROJ", "  ", null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Project name cannot be empty");
    }

    @Test
    @DisplayName("Should allow null optional fields")
    void Should_AllowNullOptionalFields() {
        // When
        var project = new JiraProject(null, "KEY", "Name", null, null, null, 0, null);

        // Then
        assertThat(project.id()).isNull();
        assertThat(project.description()).isNull();
        assertThat(project.leadDisplayName()).isNull();
        assertThat(project.url()).isNull();
        assertThat(project.lastIssueUpdateTime()).isNull();
    }
}
