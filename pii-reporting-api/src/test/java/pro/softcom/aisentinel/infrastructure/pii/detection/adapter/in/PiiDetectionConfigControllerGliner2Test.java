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
 * REST mapping tests for the {@code gliner2Enabled} flag on the singleton
 * detection config (spec §5.2). Verifies GET exposes the flag and PUT persists
 * it through to the use-case command.
 */
@WebMvcTest(PiiDetectionConfigController.class)
@Import(SecurityConfig.class)
class PiiDetectionConfigControllerGliner2Test {

    private static final String CONFIG_URL = "/api/v1/pii-detection/config";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ManagePiiDetectionConfigPort managePiiDetectionConfigPort;

    @Test
    void Should_ExposeGliner2Enabled_When_GetConfig() throws Exception {
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, true, true, true, false, true, new BigDecimal("0.75"), 35, false, false,
            LocalDateTime.now(), "admin"
        );
        when(managePiiDetectionConfigPort.getConfig()).thenReturn(config);

        mockMvc.perform(get(CONFIG_URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gliner2Enabled").value(true));
    }

    @Test
    void Should_DefaultGliner2EnabledFalse_When_GetConfig() throws Exception {
        PiiDetectionConfig config = new PiiDetectionConfig(
            1, true, true, true, false, false, new BigDecimal("0.75"), 35, false, false,
            LocalDateTime.now(), "admin"
        );
        when(managePiiDetectionConfigPort.getConfig()).thenReturn(config);

        mockMvc.perform(get(CONFIG_URL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gliner2Enabled").value(false));
    }

    @Test
    void Should_PersistGliner2Enabled_When_PutConfig() throws Exception {
        PiiDetectionConfig persisted = new PiiDetectionConfig(
            1, true, true, true, false, true, new BigDecimal("0.75"), 35, false, false,
            LocalDateTime.now(), "admin"
        );
        when(managePiiDetectionConfigPort.updateConfig(any(UpdatePiiDetectionConfigCommand.class)))
            .thenReturn(persisted);

        String body = """
                {
                  "glinerEnabled": true,
                  "presidioEnabled": true,
                  "regexEnabled": true,
                  "openmedEnabled": false,
                  "gliner2Enabled": true,
                  "defaultThreshold": 0.75,
                  "nbOfLabelByPass": 35,
                  "llmJudgeEnabled": false
                }
                """;

        mockMvc.perform(put(CONFIG_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.gliner2Enabled").value(true));

        ArgumentCaptor<UpdatePiiDetectionConfigCommand> captor =
            ArgumentCaptor.forClass(UpdatePiiDetectionConfigCommand.class);
        verify(managePiiDetectionConfigPort).updateConfig(captor.capture());
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(captor.getValue().gliner2Enabled()).isTrue();
        softly.assertAll();
    }

    @Test
    void Should_RejectUpdate_When_Gliner2EnabledMissing() throws Exception {
        // gliner2Enabled is @NotNull on the request DTO.
        String body = """
                {
                  "glinerEnabled": true,
                  "presidioEnabled": true,
                  "regexEnabled": true,
                  "openmedEnabled": false,
                  "defaultThreshold": 0.75,
                  "nbOfLabelByPass": 35
                }
                """;

        mockMvc.perform(put(CONFIG_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest());
    }
}
