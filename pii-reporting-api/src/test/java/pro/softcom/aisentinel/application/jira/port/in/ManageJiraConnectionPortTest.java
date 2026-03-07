package pro.softcom.aisentinel.application.jira.port.in;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.application.jira.port.in.ManageJiraConnectionPort.TestJiraConnectionCommand;
import pro.softcom.aisentinel.application.jira.port.in.ManageJiraConnectionPort.UpdateJiraConnectionCommand;
import pro.softcom.aisentinel.domain.jira.JiraDeploymentType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for command records in {@link ManageJiraConnectionPort}.
 * Verifies validation logic in compact constructors.
 */
class ManageJiraConnectionPortTest {

    @Nested
    class UpdateJiraConnectionCommandValidation {

        @Test
        void Should_CreateCommand_When_AllFieldsAreValid() {
            // Arrange & Act
            var command = new UpdateJiraConnectionCommand(
                "https://jira.example.com", "user@example.com", "token",
                5000, 30000, 3, 50, 5000, JiraDeploymentType.CLOUD, "admin"
            );

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(command.baseUrl()).isEqualTo("https://jira.example.com");
            softly.assertThat(command.email()).isEqualTo("user@example.com");
            softly.assertThat(command.apiToken()).isEqualTo("token");
            softly.assertThat(command.deploymentType()).isEqualTo(JiraDeploymentType.CLOUD);
            softly.assertThat(command.updatedBy()).isEqualTo("admin");
            softly.assertAll();
        }

        @Test
        void Should_ThrowNullPointerException_When_BaseUrlIsNull() {
            // Act & Assert
            assertThatThrownBy(() -> new UpdateJiraConnectionCommand(
                null, "user@example.com", "token",
                5000, 30000, 3, 50, 5000, JiraDeploymentType.CLOUD, "admin"
            ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("baseUrl must not be null");
        }

        @Test
        void Should_ThrowNullPointerException_When_EmailIsNull() {
            // Act & Assert
            assertThatThrownBy(() -> new UpdateJiraConnectionCommand(
                "https://jira.example.com", null, "token",
                5000, 30000, 3, 50, 5000, JiraDeploymentType.CLOUD, "admin"
            ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("email must not be null");
        }

        @Test
        void Should_ThrowNullPointerException_When_UpdatedByIsNull() {
            // Act & Assert
            assertThatThrownBy(() -> new UpdateJiraConnectionCommand(
                "https://jira.example.com", "user@example.com", "token",
                5000, 30000, 3, 50, 5000, JiraDeploymentType.CLOUD, null
            ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("updatedBy must not be null");
        }

        @Test
        void Should_DefaultToCloud_When_DeploymentTypeIsNull() {
            // Arrange & Act
            var command = new UpdateJiraConnectionCommand(
                "https://jira.example.com", "user@example.com", "token",
                5000, 30000, 3, 50, 5000, null, "admin"
            );

            // Assert
            assertThat(command.deploymentType())
                .as("Deployment type should default to CLOUD when null")
                .isEqualTo(JiraDeploymentType.CLOUD);
        }

        @Test
        void Should_PreserveDataCenter_When_DeploymentTypeIsDataCenter() {
            // Arrange & Act
            var command = new UpdateJiraConnectionCommand(
                "https://jira.example.com", "user@example.com", "token",
                5000, 30000, 3, 50, 5000, JiraDeploymentType.DATA_CENTER, "admin"
            );

            // Assert
            assertThat(command.deploymentType())
                .as("Deployment type should be preserved as DATA_CENTER")
                .isEqualTo(JiraDeploymentType.DATA_CENTER);
        }
    }

    @Nested
    class TestJiraConnectionCommandValidation {

        @Test
        void Should_CreateCommand_When_AllFieldsAreValid() {
            // Arrange & Act
            var command = new TestJiraConnectionCommand(
                "https://jira.example.com", "user@example.com", "token", JiraDeploymentType.CLOUD
            );

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(command.baseUrl()).isEqualTo("https://jira.example.com");
            softly.assertThat(command.email()).isEqualTo("user@example.com");
            softly.assertThat(command.apiToken()).isEqualTo("token");
            softly.assertThat(command.deploymentType()).isEqualTo(JiraDeploymentType.CLOUD);
            softly.assertAll();
        }

        @Test
        void Should_ThrowNullPointerException_When_BaseUrlIsNull() {
            // Act & Assert
            assertThatThrownBy(() -> new TestJiraConnectionCommand(
                null, "user@example.com", "token", JiraDeploymentType.CLOUD
            ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("baseUrl must not be null");
        }

        @Test
        void Should_ThrowNullPointerException_When_EmailIsNull() {
            // Act & Assert
            assertThatThrownBy(() -> new TestJiraConnectionCommand(
                "https://jira.example.com", null, "token", JiraDeploymentType.CLOUD
            ))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("email must not be null");
        }

        @Test
        void Should_DefaultToCloud_When_DeploymentTypeIsNull() {
            // Arrange & Act
            var command = new TestJiraConnectionCommand(
                "https://jira.example.com", "user@example.com", "token", null
            );

            // Assert
            assertThat(command.deploymentType())
                .as("Deployment type should default to CLOUD when null")
                .isEqualTo(JiraDeploymentType.CLOUD);
        }
    }
}
