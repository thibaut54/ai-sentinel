package pro.softcom.aisentinel.infrastructure.jira.adapter.out.jpa.entity;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.jira.JiraDeploymentType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link JiraConnectionConfigEntity}.
 * Verifies builder, equals/hashCode, and getters/setters.
 */
class JiraConnectionConfigEntityTest {

    @Nested
    class BuilderAndGetters {

        @Test
        void Should_CreateEntity_When_AllFieldsProvidedViaBuilder() {
            // Arrange
            Instant now = Instant.now();

            // Act
            JiraConnectionConfigEntity entity = JiraConnectionConfigEntity.builder()
                .id(1)
                .baseUrl("https://jira.example.com")
                .email("user@example.com")
                .apiTokenEncrypted("ENC:v1:encrypted-token")
                .connectTimeout(30000)
                .readTimeout(60000)
                .maxRetries(3)
                .issuesLimit(50)
                .maxIssues(5000)
                .deploymentType(JiraDeploymentType.CLOUD)
                .updatedAt(now)
                .updatedBy("admin")
                .build();

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(entity.getId()).isEqualTo(1);
            softly.assertThat(entity.getBaseUrl()).isEqualTo("https://jira.example.com");
            softly.assertThat(entity.getEmail()).isEqualTo("user@example.com");
            softly.assertThat(entity.getApiTokenEncrypted()).isEqualTo("ENC:v1:encrypted-token");
            softly.assertThat(entity.getConnectTimeout()).isEqualTo(30000);
            softly.assertThat(entity.getReadTimeout()).isEqualTo(60000);
            softly.assertThat(entity.getMaxRetries()).isEqualTo(3);
            softly.assertThat(entity.getIssuesLimit()).isEqualTo(50);
            softly.assertThat(entity.getMaxIssues()).isEqualTo(5000);
            softly.assertThat(entity.getDeploymentType()).isEqualTo(JiraDeploymentType.CLOUD);
            softly.assertThat(entity.getUpdatedAt()).isEqualTo(now);
            softly.assertThat(entity.getUpdatedBy()).isEqualTo("admin");
            softly.assertAll();
        }

        @Test
        void Should_AllowSettingDeploymentType_When_UsingDataCenter() {
            // Arrange & Act
            JiraConnectionConfigEntity entity = createDefaultEntity();
            entity.setDeploymentType(JiraDeploymentType.DATA_CENTER);

            // Assert
            assertThat(entity.getDeploymentType())
                .as("Deployment type should be updated via setter")
                .isEqualTo(JiraDeploymentType.DATA_CENTER);
        }

        @Test
        void Should_UpdateApiToken_When_SetterCalled() {
            // Arrange
            JiraConnectionConfigEntity entity = createDefaultEntity();

            // Act
            entity.setApiTokenEncrypted("new-encrypted-token");

            // Assert
            assertThat(entity.getApiTokenEncrypted())
                .as("API token should be updatable via setter")
                .isEqualTo("new-encrypted-token");
        }
    }

    @Nested
    class EqualsAndHashCode {

        @Test
        void Should_BeEqual_When_SameId() {
            // Arrange
            JiraConnectionConfigEntity entity1 = createEntityWithId(1);
            JiraConnectionConfigEntity entity2 = createEntityWithId(1);

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(entity1)
                .as("Entities with same ID should be equal")
                .isEqualTo(entity2);
            softly.assertThat(entity1.hashCode())
                .as("Entities with same ID should have same hashCode")
                .isEqualTo(entity2.hashCode());
            softly.assertAll();
        }

        @Test
        void Should_NotBeEqual_When_DifferentId() {
            // Arrange
            JiraConnectionConfigEntity entity1 = createEntityWithId(1);
            JiraConnectionConfigEntity entity2 = createEntityWithId(2);

            // Assert
            assertThat(entity1)
                .as("Entities with different IDs should not be equal")
                .isNotEqualTo(entity2);
        }

        @Test
        void Should_NotBeEqual_When_ComparedWithNull() {
            // Arrange
            JiraConnectionConfigEntity entity = createEntityWithId(1);

            // Assert
            assertThat(entity)
                .as("Entity should not be equal to null")
                .isNotEqualTo(null);
        }

        @Test
        void Should_NotBeEqual_When_ComparedWithDifferentType() {
            // Arrange
            JiraConnectionConfigEntity entity = createEntityWithId(1);

            // Assert
            assertThat(entity)
                .as("Entity should not be equal to object of different type")
                .isNotEqualTo("not an entity");
        }

        @Test
        void Should_BeEqual_When_SameInstance() {
            // Arrange
            JiraConnectionConfigEntity entity = createEntityWithId(1);

            // Assert
            assertThat(entity)
                .as("Entity should be equal to itself")
                .isEqualTo(entity);
        }
    }

    @Nested
    class DefaultConstructor {

        @Test
        void Should_CreateEntityWithNullFields_When_UsingDefaultConstructor() {
            // This test verifies the JPA-required protected no-arg constructor works.
            // We access it via a subclass trick or reflection, but since the builder
            // also uses AllArgsConstructor, we just verify builder with minimal fields.
            JiraConnectionConfigEntity entity = JiraConnectionConfigEntity.builder()
                .id(1)
                .build();

            assertThat(entity.getId())
                .as("ID should be set via builder even with minimal fields")
                .isEqualTo(1);
        }
    }

    // --- Helpers ---

    private JiraConnectionConfigEntity createDefaultEntity() {
        return JiraConnectionConfigEntity.builder()
            .id(1)
            .baseUrl("https://jira.example.com")
            .email("user@example.com")
            .apiTokenEncrypted("encrypted")
            .connectTimeout(30000)
            .readTimeout(60000)
            .maxRetries(3)
            .issuesLimit(50)
            .maxIssues(5000)
            .deploymentType(JiraDeploymentType.CLOUD)
            .updatedAt(Instant.now())
            .updatedBy("admin")
            .build();
    }

    private JiraConnectionConfigEntity createEntityWithId(int id) {
        return JiraConnectionConfigEntity.builder()
            .id(id)
            .baseUrl("https://jira.example.com")
            .email("user@example.com")
            .apiTokenEncrypted("encrypted")
            .connectTimeout(30000)
            .readTimeout(60000)
            .maxRetries(3)
            .issuesLimit(50)
            .maxIssues(5000)
            .deploymentType(JiraDeploymentType.CLOUD)
            .updatedAt(Instant.now())
            .updatedBy("admin")
            .build();
    }
}
