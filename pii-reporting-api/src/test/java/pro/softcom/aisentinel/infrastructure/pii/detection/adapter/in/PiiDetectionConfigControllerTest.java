package pro.softcom.aisentinel.infrastructure.pii.detection.adapter.in;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiDetectionConfigPort;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManagePiiDetectionConfigPort.UpdatePiiDetectionConfigCommand;
import pro.softcom.aisentinel.domain.pii.detection.PiiDetectionConfig;
import pro.softcom.aisentinel.infrastructure.config.SecurityConfig;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the PiiDetectionConfigController class.
 * Verifies REST mapping for the {@code llm_judge_enabled} flag (spec §1.4).
 */
@WebMvcTest(PiiDetectionConfigController.class)
@Import(SecurityConfig.class)
class PiiDetectionConfigControllerTest {

    private static final String CONFIG_URL = "/api/v1/pii-detection/config";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ManagePiiDetectionConfigPort managePiiDetectionConfigPort;

    @Test
    void Should_ReturnLlmJudgeEnabledInResponse_When_GetConfig() throws Exception {
        PiiDetectionConfig domainConfig = new PiiDetectionConfig(
            1, true, true, true, false, new BigDecimal("0.75"), 30, true,
            LocalDateTime.now(), "admin"
        );
        when(managePiiDetectionConfigPort.getConfig()).thenReturn(domainConfig);

        mockMvc.perform(get(CONFIG_URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.glinerEnabled").value(true))
            .andExpect(jsonPath("$.llmJudgeEnabled").value(true));
    }

    @Test
    void Should_DefaultLlmJudgeEnabledToFalse_When_NotProvidedInResponse() throws Exception {
        PiiDetectionConfig domainConfig = new PiiDetectionConfig(
            1, true, true, true, false, new BigDecimal("0.75"), 30, false,
            LocalDateTime.now(), "admin"
        );
        when(managePiiDetectionConfigPort.getConfig()).thenReturn(domainConfig);

        mockMvc.perform(get(CONFIG_URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.llmJudgeEnabled").value(false));
    }

    @Test
    void Should_UpdateLlmJudgeEnabled_When_PatchRequestEnablesFlag() throws Exception {
        PiiDetectionConfig persisted = new PiiDetectionConfig(
            1, true, true, true, false, new BigDecimal("0.75"), 30, true,
            LocalDateTime.now(), "admin"
        );
        when(managePiiDetectionConfigPort.updateConfig(any(UpdatePiiDetectionConfigCommand.class)))
            .thenReturn(persisted);

        String body = """
                {
                  "glinerEnabled": true,
                  "presidioEnabled": true,
                  "regexEnabled": true,
                  "defaultThreshold": 0.75,
                  "nbOfLabelByPass": 30,
                  "llmJudgeEnabled": true
                }
                """;

        mockMvc.perform(put(CONFIG_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.llmJudgeEnabled").value(true));

        ArgumentCaptor<UpdatePiiDetectionConfigCommand> captor =
            ArgumentCaptor.forClass(UpdatePiiDetectionConfigCommand.class);
        verify(managePiiDetectionConfigPort).updateConfig(captor.capture());
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(captor.getValue().llmJudgeEnabled()).isTrue();
        softly.assertThat(captor.getValue().updatedBy())
            .isEqualTo(PiiDetectionConfigController.ADMIN_USERNAME);
        softly.assertAll();
    }

    @Test
    void Should_DefaultLlmJudgeEnabledToFalse_When_OmittedInUpdateRequest() throws Exception {
        PiiDetectionConfig persisted = new PiiDetectionConfig(
            1, true, true, true, false, new BigDecimal("0.75"), 30, false,
            LocalDateTime.now(), "admin"
        );
        when(managePiiDetectionConfigPort.updateConfig(any(UpdatePiiDetectionConfigCommand.class)))
            .thenReturn(persisted);

        // No llmJudgeEnabled field in payload → must default to false at command-build time.
        String body = """
                {
                  "glinerEnabled": true,
                  "presidioEnabled": true,
                  "regexEnabled": true,
                  "defaultThreshold": 0.75,
                  "nbOfLabelByPass": 30
                }
                """;

        mockMvc.perform(put(CONFIG_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.llmJudgeEnabled").value(false));

        ArgumentCaptor<UpdatePiiDetectionConfigCommand> captor =
            ArgumentCaptor.forClass(UpdatePiiDetectionConfigCommand.class);
        verify(managePiiDetectionConfigPort).updateConfig(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().llmJudgeEnabled()).isFalse();
    }
}
