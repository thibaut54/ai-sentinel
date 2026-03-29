package pro.softcom.aisentinel.infrastructure.confluence.adapter.in;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import pro.softcom.aisentinel.application.confluence.port.in.ManageConfluenceConnectionPort;
import pro.softcom.aisentinel.domain.confluence.ConfluenceConnectionSettings;
import pro.softcom.aisentinel.domain.confluence.ConfluenceDeploymentType;
import pro.softcom.aisentinel.infrastructure.config.SecurityConfig;

import java.time.Instant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfluenceConnectionConfigController.class)
@Import(SecurityConfig.class)
class ConfluenceConnectionConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ManageConfluenceConnectionPort manageConfluenceConnectionPort;

    @Test
    void Should_NotReturnApiToken_When_GetConfig() throws Exception {
        // Arrange
        var settings = new ConfluenceConnectionSettings(
                1, "https://confluence.example.com", "user@example.com",
                30000, 60000, 3, 25, 1000,
                ConfluenceDeploymentType.CLOUD,
                Instant.parse("2026-01-01T00:00:00Z"), "admin"
        );
        when(manageConfluenceConnectionPort.getConnectionSettings()).thenReturn(settings);
        when(manageConfluenceConnectionPort.isConfigured()).thenReturn(true);

        // Act
        var mvcResult = mockMvc.perform(get("/api/v1/confluence/connection-config"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Assert - no token field should be present
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiTokenMasked").doesNotExist())
                .andExpect(jsonPath("$.apiToken").doesNotExist())
                .andExpect(jsonPath("$.baseUrl").value("https://confluence.example.com"))
                .andExpect(jsonPath("$.configured").value(true));
    }
}
