package pro.softcom.aisentinel.domain.pii.detection;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for PiiDetectionConfig domain model.
 */
class PiiDetectionConfigTest {

    @Test
    void Should_CreateConfig_When_AllParametersValid() {
        // Arrange
        Integer id = 1;
        boolean glinerEnabled = true;
        boolean presidioEnabled = true;
        boolean regexEnabled = false;
        boolean openmedEnabled = false;
        BigDecimal threshold = new BigDecimal("0.75");
        LocalDateTime updatedAt = LocalDateTime.now();
        String updatedBy = "testuser";

        // Act
        PiiDetectionConfig config = new PiiDetectionConfig(
            id, glinerEnabled, presidioEnabled, regexEnabled, false, false, threshold, 30, false, false, updatedAt, updatedBy
        );

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(config.id()).isEqualTo(id);
        softly.assertThat(config.glinerEnabled()).isEqualTo(glinerEnabled);
        softly.assertThat(config.presidioEnabled()).isEqualTo(presidioEnabled);
        softly.assertThat(config.regexEnabled()).isEqualTo(regexEnabled);
        softly.assertThat(config.openmedEnabled()).isEqualTo(openmedEnabled);
        softly.assertThat(config.defaultThreshold()).isEqualByComparingTo(threshold);
        softly.assertThat(config.nbOfLabelByPass()).isEqualTo(30);
        softly.assertThat(config.llmJudgeEnabled()).isFalse();
        softly.assertThat(config.prefilterEnabled()).isFalse();
        softly.assertThat(config.updatedAt()).isEqualTo(updatedAt);
        softly.assertThat(config.updatedBy()).isEqualTo(updatedBy);
        softly.assertAll();
    }

    @Test
    void Should_ExposeLlmJudgeEnabledFlag_When_Enabled() {
        // Arrange & Act
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, true, true, true, false, false, new BigDecimal("0.75"), 30, true, false, LocalDateTime.now(), "system"
        );

        // Assert
        assertThat(config.llmJudgeEnabled()).isTrue();
    }

    @Test
    void Should_ExposePrefilterEnabledFlag_When_Enabled() {
        // Arrange & Act
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, true, true, true, false, false, new BigDecimal("0.75"), 30, false, true, LocalDateTime.now(), "system"
        );

        // Assert
        assertThat(config.prefilterEnabled()).isTrue();
    }

    @Test
    void Should_ThrowException_When_ThresholdIsNull() {
        LocalDateTime now = LocalDateTime.now();
        // Arrange & Act & Assert
        assertThatThrownBy(() -> new PiiDetectionConfig(
            1, true, true, true, false, false, null, 30, false, false, now, "testuser"
        ))
        .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Should_ThrowException_When_ThresholdLessThanZero() {
        LocalDateTime now = LocalDateTime.now();
        BigDecimal defaultThreshold = new BigDecimal("-0.75");
        // Arrange & Act & Assert
        assertThatThrownBy(() -> new PiiDetectionConfig(
            1, true, true, true, false, false, defaultThreshold, 30, false, false, now, "testuser"
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Default threshold must be greater than or equal to 0");
    }

    @Test
    void Should_ThrowException_When_ThresholdGreaterThanOne() {
        LocalDateTime now = LocalDateTime.now();
        BigDecimal defaultThreshold = new BigDecimal("1.1");
        // Arrange & Act & Assert
        assertThatThrownBy(() -> new PiiDetectionConfig(
            1, true, true, true, false, false, defaultThreshold, 30, false, false, now, "testuser"
        ))
        .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void Should_AcceptThreshold_When_ThresholdIsZero() {
        // Arrange & Act
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, true, false, false, false, false, BigDecimal.ZERO, 30, false, false, LocalDateTime.now(), "testuser"
        );

        // Assert
        assertThat(config.defaultThreshold()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void Should_AcceptThreshold_When_ThresholdIsOne() {
        // Arrange & Act
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, true, false, false, false, false, BigDecimal.ONE, 30, false, false, LocalDateTime.now(), "testuser"
        );

        // Assert
        assertThat(config.defaultThreshold()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void Should_ThrowException_When_NoDetectorsEnabled() {
        LocalDateTime now = LocalDateTime.now();
        BigDecimal defaultThreshold = new BigDecimal("0.75");
        // Arrange & Act & Assert
        assertThatThrownBy(() -> new PiiDetectionConfig(
            1, false, false, false, false, false, defaultThreshold, 30, false, false, now, "testuser"
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At least one detector must be enabled");
    }

    @Test
    void Should_AcceptConfig_When_OnlyGlinerEnabled() {
        // Arrange & Act
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, true, false, false, false, false, new BigDecimal("0.75"), 30, false, false, LocalDateTime.now(), "testuser"
        );

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(config.glinerEnabled()).isTrue();
        softly.assertThat(config.presidioEnabled()).isFalse();
        softly.assertThat(config.regexEnabled()).isFalse();
        softly.assertThat(config.openmedEnabled()).isFalse();
        softly.assertAll();
    }

    @Test
    void Should_AcceptConfig_When_OnlyPresidioEnabled() {
        // Arrange & Act
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, false, true, false, false, false, new BigDecimal("0.75"), 30, false, false, LocalDateTime.now(), "testuser"
        );

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(config.glinerEnabled()).isFalse();
        softly.assertThat(config.presidioEnabled()).isTrue();
        softly.assertThat(config.regexEnabled()).isFalse();
        softly.assertThat(config.openmedEnabled()).isFalse();
        softly.assertAll();
    }

    @Test
    void Should_AcceptConfig_When_OnlyRegexEnabled() {
        // Arrange & Act
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, false, false, true, false, false, new BigDecimal("0.75"), 30, false, false, LocalDateTime.now(), "testuser"
        );

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(config.glinerEnabled()).isFalse();
        softly.assertThat(config.presidioEnabled()).isFalse();
        softly.assertThat(config.regexEnabled()).isTrue();
        softly.assertThat(config.openmedEnabled()).isFalse();
        softly.assertAll();
    }

    @Test
    void should_AcceptConfig_When_OnlyOpenmedEnabled() {
        // Arrange & Act
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, false, false, false, true, false, new BigDecimal("0.75"), 30, false, false, LocalDateTime.now(), "testuser"
        );

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(config.glinerEnabled()).isFalse();
        softly.assertThat(config.presidioEnabled()).isFalse();
        softly.assertThat(config.regexEnabled()).isFalse();
        softly.assertThat(config.openmedEnabled()).isTrue();
        softly.assertAll();
    }

    @Test
    void Should_BeEqual_When_AllFieldsMatch() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        PiiDetectionConfig config1 = new PiiDetectionConfig(
            1, true, true, false, false, false, new BigDecimal("0.75"), 30, true, false, now, "testuser"
        );
        PiiDetectionConfig config2 = new PiiDetectionConfig(
            1, true, true, false, false, false, new BigDecimal("0.75"), 30, true, false, now, "testuser"
        );

        // Act & Assert
        assertThat(config1)
            .isEqualTo(config2)
            .hasSameHashCodeAs(config2);
    }

    @Test
    void Should_NotBeEqual_When_LlmJudgeEnabledDiffers() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        PiiDetectionConfig configOff = new PiiDetectionConfig(
            1, true, true, false, false, false, new BigDecimal("0.75"), 30, false, false, now, "testuser"
        );
        PiiDetectionConfig configOn = new PiiDetectionConfig(
            1, true, true, false, false, false, new BigDecimal("0.75"), 30, true, false, now, "testuser"
        );

        // Act & Assert
        assertThat(configOff).isNotEqualTo(configOn);
    }

    @Test
    void Should_NotBeEqual_When_PrefilterEnabledDiffers() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        PiiDetectionConfig configOff = new PiiDetectionConfig(
            1, true, true, false, false, false, new BigDecimal("0.75"), 30, false, false, now, "testuser"
        );
        PiiDetectionConfig configOn = new PiiDetectionConfig(
            1, true, true, false, false, false, new BigDecimal("0.75"), 30, false, true, now, "testuser"
        );

        // Act & Assert
        assertThat(configOff).isNotEqualTo(configOn);
    }

    @Test
    void Should_NotBeEqual_When_ThresholdDiffers() {
        // Arrange
        LocalDateTime now = LocalDateTime.now();
        PiiDetectionConfig config1 = new PiiDetectionConfig(
            1, true, true, false, false, false, new BigDecimal("0.75"), 30, false, false, now, "testuser"
        );
        PiiDetectionConfig config2 = new PiiDetectionConfig(
            1, true, true, false, false, false, new BigDecimal("0.80"), 30, false, false, now, "testuser"
        );

        // Act & Assert
        assertThat(config1).isNotEqualTo(config2);
    }

    @Test
    void Should_ReturnValidString_When_ToStringCalled() {
        // Arrange
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, true, true, false, false, false, new BigDecimal("0.75"), 30, true, false, LocalDateTime.now(), "testuser"
        );

        // Act
        String result = config.toString();

        // Assert
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).contains("PiiDetectionConfig[");
        softly.assertThat(result).contains("id=1");
        softly.assertThat(result).contains("glinerEnabled=true");
        softly.assertThat(result).contains("presidioEnabled=true");
        softly.assertThat(result).contains("regexEnabled=false");
        softly.assertThat(result).contains("openmedEnabled=false");
        softly.assertThat(result).contains("defaultThreshold=0.75");
        softly.assertThat(result).contains("llmJudgeEnabled=true");
        softly.assertThat(result).contains("prefilterEnabled=false");
        softly.assertThat(result).contains("updatedBy=testuser");
        softly.assertAll();
    }
}
