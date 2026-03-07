package pro.softcom.aisentinel.domain.jira;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link JiraDeploymentType}.
 * Verifies enum values and valueOf behavior.
 */
class JiraDeploymentTypeTest {

    @Nested
    class EnumValues {

        @Test
        void Should_HaveTwoValues_When_ListingAll() {
            // Act
            JiraDeploymentType[] values = JiraDeploymentType.values();

            // Assert
            assertThat(values)
                .as("JiraDeploymentType should have exactly 2 values: CLOUD and DATA_CENTER")
                .hasSize(2)
                .containsExactly(JiraDeploymentType.CLOUD, JiraDeploymentType.DATA_CENTER);
        }

        @Test
        void Should_ReturnCloud_When_ValueOfCloud() {
            // Act
            JiraDeploymentType result = JiraDeploymentType.valueOf("CLOUD");

            // Assert
            assertThat(result)
                .as("valueOf('CLOUD') should return CLOUD")
                .isEqualTo(JiraDeploymentType.CLOUD);
        }

        @Test
        void Should_ReturnDataCenter_When_ValueOfDataCenter() {
            // Act
            JiraDeploymentType result = JiraDeploymentType.valueOf("DATA_CENTER");

            // Assert
            assertThat(result)
                .as("valueOf('DATA_CENTER') should return DATA_CENTER")
                .isEqualTo(JiraDeploymentType.DATA_CENTER);
        }

        @Test
        void Should_ThrowException_When_ValueOfInvalid() {
            // Act & Assert
            assertThatThrownBy(() -> JiraDeploymentType.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
