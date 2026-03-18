package pro.softcom.aisentinel.domain.confluence;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfluenceDeploymentTypeTest {

    @Test
    void Should_HaveExactlyTwoValues() {
        assertThat(ConfluenceDeploymentType.values()).hasSize(2);
    }

    @Test
    void Should_ContainCloudAndDataCenter() {
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(ConfluenceDeploymentType.valueOf("CLOUD")).isEqualTo(ConfluenceDeploymentType.CLOUD);
        softly.assertThat(ConfluenceDeploymentType.valueOf("DATA_CENTER")).isEqualTo(ConfluenceDeploymentType.DATA_CENTER);
        softly.assertAll();
    }

    @Test
    void Should_ReturnCorrectName_When_CallingName() {
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(ConfluenceDeploymentType.CLOUD.name()).isEqualTo("CLOUD");
        softly.assertThat(ConfluenceDeploymentType.DATA_CENTER.name()).isEqualTo("DATA_CENTER");
        softly.assertAll();
    }

    @Test
    void Should_ThrowException_When_InvalidValueProvided() {
        assertThatThrownBy(() -> ConfluenceDeploymentType.valueOf("INVALID"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Should_ReturnCorrectOrdinals() {
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(ConfluenceDeploymentType.CLOUD.ordinal()).isZero();
        softly.assertThat(ConfluenceDeploymentType.DATA_CENTER.ordinal()).isEqualTo(1);
        softly.assertAll();
    }
}
