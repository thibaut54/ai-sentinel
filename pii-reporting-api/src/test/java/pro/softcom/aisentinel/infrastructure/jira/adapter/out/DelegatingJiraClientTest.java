package pro.softcom.aisentinel.infrastructure.jira.adapter.out;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.jira.port.out.JiraClient;
import pro.softcom.aisentinel.domain.jira.*;
import pro.softcom.aisentinel.infrastructure.jira.adapter.out.config.JiraConnectionConfig;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DelegatingJiraClient}.
 * Verifies correct delegation to Cloud or Data Center adapter based on deployment type.
 */
@ExtendWith(MockitoExtension.class)
class DelegatingJiraClientTest {

    @Mock
    private JiraConnectionConfig config;

    @Mock
    private JiraClient cloudAdapter;

    @Mock
    private JiraClient dataCenterAdapter;

    private DelegatingJiraClient delegatingClient;

    @BeforeEach
    void setUp() {
        delegatingClient = new DelegatingJiraClient(config, cloudAdapter, dataCenterAdapter);
    }

    @Nested
    class CloudDelegation {

        @BeforeEach
        void setUpCloudConfig() {
            when(config.deploymentType()).thenReturn(JiraDeploymentType.CLOUD);
        }

        @Test
        void Should_DelegateToCloudAdapter_When_DeploymentTypeIsCloud() throws Exception {
            // Arrange
            when(cloudAdapter.testConnection())
                .thenReturn(CompletableFuture.completedFuture(true));

            // Act
            Boolean result = delegatingClient.testConnection().get();

            // Assert
            assertThat(result)
                .as("Test connection should delegate to cloud adapter")
                .isTrue();
            verify(cloudAdapter).testConnection();
            verifyNoInteractions(dataCenterAdapter);
        }

        @Test
        void Should_DelegateGetAllProjectsToCloud_When_DeploymentTypeIsCloud() throws Exception {
            // Arrange
            List<JiraProject> projects = List.of(
                new JiraProject("1", "PROJ", "Project", "desc", "Lead", "url", 10, null)
            );
            when(cloudAdapter.getAllProjects())
                .thenReturn(CompletableFuture.completedFuture(projects));

            // Act
            List<JiraProject> result = delegatingClient.getAllProjects().get();

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result).hasSize(1);
            softly.assertThat(result.get(0).key()).isEqualTo("PROJ");
            softly.assertAll();
            verify(cloudAdapter).getAllProjects();
            verifyNoInteractions(dataCenterAdapter);
        }

        @Test
        void Should_DelegateGetIssuesInProjectToCloud_When_DeploymentTypeIsCloud() throws Exception {
            // Arrange
            List<JiraIssue> issues = List.of(
                JiraIssue.builder().id("1").key("PROJ-1").projectKey("PROJ").summary("Bug fix").build()
            );
            when(cloudAdapter.getIssuesInProject("PROJ"))
                .thenReturn(CompletableFuture.completedFuture(issues));

            // Act
            List<JiraIssue> result = delegatingClient.getIssuesInProject("PROJ").get();

            // Assert
            assertThat(result).hasSize(1);
            verify(cloudAdapter).getIssuesInProject("PROJ");
            verifyNoInteractions(dataCenterAdapter);
        }
    }

    @Nested
    class DataCenterDelegation {

        @BeforeEach
        void setUpDataCenterConfig() {
            when(config.deploymentType()).thenReturn(JiraDeploymentType.DATA_CENTER);
        }

        @Test
        void Should_DelegateToDataCenterAdapter_When_DeploymentTypeIsDataCenter() throws Exception {
            // Arrange
            when(dataCenterAdapter.testConnection())
                .thenReturn(CompletableFuture.completedFuture(true));

            // Act
            Boolean result = delegatingClient.testConnection().get();

            // Assert
            assertThat(result)
                .as("Test connection should delegate to data center adapter")
                .isTrue();
            verify(dataCenterAdapter).testConnection();
            verifyNoInteractions(cloudAdapter);
        }

        @Test
        void Should_DelegateGetAllProjectsToDataCenter_When_DeploymentTypeIsDataCenter() throws Exception {
            // Arrange
            List<JiraProject> projects = List.of(
                new JiraProject("1", "DC", "DC Project", "desc", "Lead", "url", 5, null)
            );
            when(dataCenterAdapter.getAllProjects())
                .thenReturn(CompletableFuture.completedFuture(projects));

            // Act
            List<JiraProject> result = delegatingClient.getAllProjects().get();

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result).hasSize(1);
            softly.assertThat(result.get(0).key()).isEqualTo("DC");
            softly.assertAll();
            verify(dataCenterAdapter).getAllProjects();
            verifyNoInteractions(cloudAdapter);
        }

        @Test
        void Should_DelegateGetIssuesUpdatedSinceToDataCenter_When_DeploymentTypeIsDataCenter() throws Exception {
            // Arrange
            Instant since = Instant.parse("2024-01-01T00:00:00Z");
            when(dataCenterAdapter.getIssuesUpdatedSince("DC", since))
                .thenReturn(CompletableFuture.completedFuture(List.of()));

            // Act
            List<JiraIssue> result = delegatingClient.getIssuesUpdatedSince("DC", since).get();

            // Assert
            assertThat(result).isEmpty();
            verify(dataCenterAdapter).getIssuesUpdatedSince("DC", since);
            verifyNoInteractions(cloudAdapter);
        }
    }

    @Nested
    class AllMethodsDelegateCorrectly {

        @Test
        void Should_DelegateGetAllComments_When_Called() throws Exception {
            // Arrange
            when(config.deploymentType()).thenReturn(JiraDeploymentType.CLOUD);
            List<JiraComment> comments = List.of(
                new JiraComment("c1", "Author", "Body", Instant.now(), null)
            );
            when(cloudAdapter.getAllComments("PROJ-1"))
                .thenReturn(CompletableFuture.completedFuture(comments));

            // Act
            List<JiraComment> result = delegatingClient.getAllComments("PROJ-1").get();

            // Assert
            assertThat(result).hasSize(1);
            verify(cloudAdapter).getAllComments("PROJ-1");
        }

        @Test
        void Should_DelegateGetAttachments_When_Called() throws Exception {
            // Arrange
            when(config.deploymentType()).thenReturn(JiraDeploymentType.CLOUD);
            List<JiraAttachmentInfo> attachments = List.of(
                new JiraAttachmentInfo("a1", "file.pdf", "application/pdf", 1024, "url", "Author")
            );
            when(cloudAdapter.getAttachments("PROJ-1"))
                .thenReturn(CompletableFuture.completedFuture(attachments));

            // Act
            List<JiraAttachmentInfo> result = delegatingClient.getAttachments("PROJ-1").get();

            // Assert
            assertThat(result).hasSize(1);
            verify(cloudAdapter).getAttachments("PROJ-1");
        }

        @Test
        void Should_DelegateGetAttachmentContent_When_Called() throws Exception {
            // Arrange
            when(config.deploymentType()).thenReturn(JiraDeploymentType.DATA_CENTER);
            byte[] content = new byte[]{1, 2, 3};
            when(dataCenterAdapter.getAttachmentContent("att-1"))
                .thenReturn(CompletableFuture.completedFuture(content));

            // Act
            byte[] result = delegatingClient.getAttachmentContent("att-1").get();

            // Assert
            assertThat(result).isEqualTo(new byte[]{1, 2, 3});
            verify(dataCenterAdapter).getAttachmentContent("att-1");
        }
    }
}
