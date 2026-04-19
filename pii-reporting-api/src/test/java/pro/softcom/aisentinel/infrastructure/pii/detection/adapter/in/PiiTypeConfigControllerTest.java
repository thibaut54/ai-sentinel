package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiTypeConfigsPort;
import pro.softcom.aisentinel.domain.pii.detection.PiiTypeConfig;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto.CategoryGroupResponseDto;
import pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in.dto.GroupedPiiTypesResponseDto;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PiiTypeConfigController")
class PiiTypeConfigControllerTest {

    @Mock
    private ManagePiiTypeConfigsPort managePiiTypeConfigsPort;

    @InjectMocks
    private PiiTypeConfigController controller;

    @Test
    @DisplayName("Should_IncludeRegexDetector_When_GetGroupedForUI")
    void Should_IncludeRegexDetector_When_GetGroupedForUI() {
        // Arrange
        var glinerConfig = PiiTypeConfig.builder()
                .piiType("EMAIL")
                .detector("GLINER")
                .enabled(true)
                .threshold(0.80)
                .category("CONTACT")
                .detectorLabel("email address")
                .severity("LOW")
                .build();
        var presidioConfig = PiiTypeConfig.builder()
                .piiType("CREDIT_CARD")
                .detector("PRESIDIO")
                .enabled(true)
                .threshold(0.75)
                .category("Financial")
                .detectorLabel("CREDIT_CARD")
                .severity("HIGH")
                .build();
        var regexConfig = PiiTypeConfig.builder()
                .piiType("SOCIALNUM")
                .detector("REGEX")
                .enabled(true)
                .threshold(0.75)
                .category("IDENTITY")
                .detectorLabel("social security number")
                .severity("HIGH")
                .build();

        when(managePiiTypeConfigsPort.getAllConfigs())
                .thenReturn(List.of(glinerConfig, presidioConfig, regexConfig));

        // Act
        var response = controller.getGroupedForUI();

        // Assert
        assertSoftly(softly -> {
            softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            List<GroupedPiiTypesResponseDto> body = response.getBody();
            softly.assertThat(body).isNotNull();
            softly.assertThat(body).hasSize(3);

            Assertions.assertNotNull(body);
            List<String> detectors = body.stream()
                    .map(GroupedPiiTypesResponseDto::detector)
                    .toList();
            softly.assertThat(detectors).containsExactly("GLINER", "PRESIDIO", "REGEX");
        });
    }

    @Test
    @DisplayName("Should_GroupByDetectorThenCategory_When_GetGroupedForUI")
    void Should_GroupByDetectorThenCategory_When_GetGroupedForUI() {
        // Arrange
        var regexIdentity = PiiTypeConfig.builder()
                .piiType("SOCIALNUM")
                .detector("REGEX")
                .enabled(true)
                .threshold(0.75)
                .category("IDENTITY")
                .detectorLabel("social security number")
                .severity("HIGH")
                .build();
        var regexFinancial = PiiTypeConfig.builder()
                .piiType("CREDIT_CARD_NUMBER")
                .detector("REGEX")
                .enabled(true)
                .threshold(0.90)
                .category("FINANCIAL")
                .detectorLabel("credit card number")
                .severity("HIGH")
                .build();

        when(managePiiTypeConfigsPort.getAllConfigs())
                .thenReturn(List.of(regexIdentity, regexFinancial));

        // Act
        var response = controller.getGroupedForUI();

        // Assert
        var body = response.getBody();
        assertThat(body).hasSize(1);

        var regexGroup = body.getFirst();
        assertSoftly(softly -> {
            softly.assertThat(regexGroup.detector()).isEqualTo("REGEX");
            softly.assertThat(regexGroup.categories()).hasSize(2);

            List<String> categories = regexGroup.categories().stream()
                    .map(CategoryGroupResponseDto::category)
                    .toList();
            softly.assertThat(categories).containsExactly("FINANCIAL", "IDENTITY");
        });
    }

    @Test
    @DisplayName("Should_ReturnEmptyList_When_NoConfigsExist")
    void Should_ReturnEmptyList_When_NoConfigsExist() {
        // Arrange
        when(managePiiTypeConfigsPort.getAllConfigs()).thenReturn(Collections.emptyList());

        // Act
        var response = controller.getGroupedForUI();

        // Assert
        assertSoftly(softly -> {
            softly.assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            softly.assertThat(response.getBody()).isEmpty();
        });
    }

    @Test
    @DisplayName("Should_SortDetectorsAlphabetically_When_GetGroupedForUI")
    void Should_SortDetectorsAlphabetically_When_GetGroupedForUI() {
        // Arrange - insert in reverse order to verify sorting
        var regexConfig = PiiTypeConfig.builder()
                .piiType("SOCIALNUM").detector("REGEX").enabled(true)
                .threshold(0.75).category("IDENTITY").detectorLabel("social security number")
                .severity("HIGH").build();
        var glinerConfig = PiiTypeConfig.builder()
                .piiType("EMAIL").detector("GLINER").enabled(true)
                .threshold(0.80).category("CONTACT").detectorLabel("email address")
                .severity("LOW").build();
        var presidioConfig = PiiTypeConfig.builder()
                .piiType("CREDIT_CARD").detector("PRESIDIO").enabled(true)
                .threshold(0.75).category("Financial").detectorLabel("CREDIT_CARD")
                .severity("HIGH").build();

        when(managePiiTypeConfigsPort.getAllConfigs())
                .thenReturn(List.of(regexConfig, glinerConfig, presidioConfig));

        // Act
        var response = controller.getGroupedForUI();

        // Assert
        Assertions.assertNotNull(response.getBody());
        List<String> detectors = response.getBody().stream()
                .map(GroupedPiiTypesResponseDto::detector)
                .toList();
        assertThat(detectors).containsExactly("GLINER", "PRESIDIO", "REGEX");
    }
}
