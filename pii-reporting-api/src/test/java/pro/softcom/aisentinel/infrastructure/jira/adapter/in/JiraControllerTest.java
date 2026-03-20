package pro.softcom.aisentinel.infrastructure.jira.adapter.in;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import pro.softcom.aisentinel.application.jira.port.in.JiraProjectPort;
import pro.softcom.aisentinel.application.jira.port.out.JiraClient;
import pro.softcom.aisentinel.domain.jira.JiraProject;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JiraController")
class JiraControllerTest {

    @Mock
    private JiraProjectPort jiraProjectPort;

    @Mock
    private JiraClient jiraClient;

    private JiraController controller;

    @BeforeEach
    void setUp() {
        controller = new JiraController(jiraProjectPort, jiraClient);
    }

    @Nested
    @DisplayName("GET /health")
    class HealthCheck {

        @Test
        @DisplayName("Should_ReturnUp_When_ConnectionSucceeds")
        void Should_ReturnUp_When_ConnectionSucceeds() throws Exception {
            when(jiraClient.testConnection()).thenReturn(CompletableFuture.completedFuture(true));

            ResponseEntity<JiraController.JiraHealthCheckResponse> response = controller.checkHealth().get();

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode().value()).isEqualTo(200);
                softly.assertThat(response.getBody()).isNotNull();
                softly.assertThat(response.getBody().status()).isEqualTo("UP");
                softly.assertThat(response.getBody().message()).contains("established");
            });
        }

        @Test
        @DisplayName("Should_ReturnDown_When_ConnectionFails")
        void Should_ReturnDown_When_ConnectionFails() throws Exception {
            when(jiraClient.testConnection()).thenReturn(CompletableFuture.completedFuture(false));

            ResponseEntity<JiraController.JiraHealthCheckResponse> response = controller.checkHealth().get();

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode().value()).isEqualTo(503);
                softly.assertThat(response.getBody()).isNotNull();
                softly.assertThat(response.getBody().status()).isEqualTo("DOWN");
                softly.assertThat(response.getBody().message()).contains("not accessible");
            });
        }
    }

    @Nested
    @DisplayName("GET /projects")
    class GetAllProjects {

        @Test
        @DisplayName("Should_ReturnProjects_When_ProjectsExist")
        void Should_ReturnProjects_When_ProjectsExist() throws Exception {
            List<JiraProject> projects = List.of(
                    createProject("PROJ", "My Project"),
                    createProject("TEST", "Test Project")
            );
            when(jiraProjectPort.getAllProjects()).thenReturn(projects);

            ResponseEntity<List<JiraProject>> response = controller.getAllProjects().get();

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode().value()).isEqualTo(200);
                softly.assertThat(response.getBody()).hasSize(2);
                softly.assertThat(response.getBody().get(0).key()).isEqualTo("PROJ");
                softly.assertThat(response.getBody().get(1).key()).isEqualTo("TEST");
            });
        }

        @Test
        @DisplayName("Should_ReturnEmptyList_When_NoProjects")
        void Should_ReturnEmptyList_When_NoProjects() throws Exception {
            when(jiraProjectPort.getAllProjects()).thenReturn(List.of());

            ResponseEntity<List<JiraProject>> response = controller.getAllProjects().get();

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode().value()).isEqualTo(200);
                softly.assertThat(response.getBody()).isEmpty();
            });
        }

        @Test
        @DisplayName("Should_Return500_When_ErrorOccurs")
        void Should_Return500_When_ErrorOccurs() throws Exception {
            when(jiraProjectPort.getAllProjects()).thenThrow(new RuntimeException("DB error"));

            ResponseEntity<List<JiraProject>> response = controller.getAllProjects().get();

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode().value()).isEqualTo(500);
            });
        }
    }

    @Nested
    @DisplayName("GET /projects/{projectKey}")
    class GetProject {

        @Test
        @DisplayName("Should_ReturnProject_When_ProjectExists")
        void Should_ReturnProject_When_ProjectExists() throws Exception {
            JiraProject project = createProject("PROJ", "My Project");
            when(jiraProjectPort.getProject("PROJ")).thenReturn(project);

            ResponseEntity<JiraProject> response = controller.getProject("PROJ").get();

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode().value()).isEqualTo(200);
                softly.assertThat(response.getBody()).isNotNull();
                softly.assertThat(response.getBody().key()).isEqualTo("PROJ");
                softly.assertThat(response.getBody().name()).isEqualTo("My Project");
            });
        }

        @Test
        @DisplayName("Should_Return404_When_ProjectNotFound")
        void Should_Return404_When_ProjectNotFound() throws Exception {
            when(jiraProjectPort.getProject("MISSING")).thenThrow(new NoSuchElementException("Not found"));

            ResponseEntity<JiraProject> response = controller.getProject("MISSING").get();

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode().value()).isEqualTo(404);
            });
        }

        @Test
        @DisplayName("Should_Return500_When_UnexpectedErrorOccurs")
        void Should_Return500_When_UnexpectedErrorOccurs() throws Exception {
            when(jiraProjectPort.getProject(anyString())).thenThrow(new RuntimeException("Internal error"));

            ResponseEntity<JiraProject> response = controller.getProject("PROJ").get();

            assertSoftly(softly -> {
                softly.assertThat(response.getStatusCode().value()).isEqualTo(500);
            });
        }
    }

    private JiraProject createProject(String key, String name) {
        return new JiraProject("10001", key, name, "Description", "Lead", "https://jira.test", 42, Instant.now());
    }
}
