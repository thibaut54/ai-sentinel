package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceClient;
import pro.softcom.aisentinel.domain.confluence.ConfluenceDeploymentType;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage;
import pro.softcom.aisentinel.domain.confluence.ConfluenceSpace;
import pro.softcom.aisentinel.domain.confluence.ModifiedAttachmentInfo;
import pro.softcom.aisentinel.domain.confluence.ModifiedPageInfo;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DelegatingConfluenceClientTest {

    @Mock
    private ConfluenceConnectionConfig config;

    @Mock
    private ConfluenceClient cloudAdapter;

    @Mock
    private ConfluenceClient dataCenterAdapter;

    private DelegatingConfluenceClient delegatingClient;

    @BeforeEach
    void setUp() {
        delegatingClient = new DelegatingConfluenceClient(config, cloudAdapter, dataCenterAdapter);
    }

    // --- Cloud delegation tests ---

    @Test
    void Should_DelegateToCloud_When_DeploymentTypeIsCloud() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);
        when(cloudAdapter.testConnection()).thenReturn(CompletableFuture.completedFuture(true));

        boolean result = delegatingClient.testConnection().join();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).isTrue();
        softly.assertAll();
        verify(cloudAdapter).testConnection();
        verifyNoInteractions(dataCenterAdapter);
    }

    @Test
    void Should_DelegateToCloud_When_DeploymentTypeIsNull() {
        when(config.deploymentType()).thenReturn(null);
        when(cloudAdapter.testConnection()).thenReturn(CompletableFuture.completedFuture(true));

        boolean result = delegatingClient.testConnection().join();

        assertThat(result).isTrue();
        verify(cloudAdapter).testConnection();
        verifyNoInteractions(dataCenterAdapter);
    }

    // --- Data Center delegation tests ---

    @Test
    void Should_DelegateToDataCenter_When_DeploymentTypeIsDataCenter() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);
        when(dataCenterAdapter.testConnection()).thenReturn(CompletableFuture.completedFuture(true));

        boolean result = delegatingClient.testConnection().join();

        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(result).isTrue();
        softly.assertAll();
        verify(dataCenterAdapter).testConnection();
        verifyNoInteractions(cloudAdapter);
    }

    // --- All ConfluenceClient methods delegation ---

    @Test
    void Should_DelegateGetPage_When_CalledWithCloud() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);
        var expected = CompletableFuture.completedFuture(Optional.<ConfluencePage>empty());
        when(cloudAdapter.getPage("123")).thenReturn(expected);

        var result = delegatingClient.getPage("123");

        assertThat(result).isSameAs(expected);
        verify(cloudAdapter).getPage("123");
    }

    @Test
    void Should_DelegateGetPage_When_CalledWithDataCenter() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);
        var expected = CompletableFuture.completedFuture(Optional.<ConfluencePage>empty());
        when(dataCenterAdapter.getPage("123")).thenReturn(expected);

        var result = delegatingClient.getPage("123");

        assertThat(result).isSameAs(expected);
        verify(dataCenterAdapter).getPage("123");
    }

    @Test
    void Should_DelegateSearchPages_When_CalledWithCloud() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);
        var expected = CompletableFuture.completedFuture(List.<ConfluencePage>of());
        when(cloudAdapter.searchPages("KEY", "query")).thenReturn(expected);

        var result = delegatingClient.searchPages("KEY", "query");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void Should_DelegateUpdatePage_When_CalledWithDataCenter() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);
        var page = ConfluencePage.builder().id("1").title("T").build();
        var expected = CompletableFuture.completedFuture(page);
        when(dataCenterAdapter.updatePage(page)).thenReturn(expected);

        var result = delegatingClient.updatePage(page);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void Should_DelegateGetSpace_When_CalledWithCloud() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);
        var expected = CompletableFuture.completedFuture(Optional.<ConfluenceSpace>empty());
        when(cloudAdapter.getSpace("KEY")).thenReturn(expected);

        var result = delegatingClient.getSpace("KEY");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void Should_DelegateGetSpaceWithPermissions_When_CalledWithDataCenter() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);
        var expected = CompletableFuture.completedFuture(Optional.<ConfluenceSpace>empty());
        when(dataCenterAdapter.getSpaceWithPermissions("KEY")).thenReturn(expected);

        var result = delegatingClient.getSpaceWithPermissions("KEY");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void Should_DelegateGetSpaceById_When_CalledWithCloud() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);
        var expected = CompletableFuture.completedFuture(Optional.<ConfluenceSpace>empty());
        when(cloudAdapter.getSpaceById("42")).thenReturn(expected);

        var result = delegatingClient.getSpaceById("42");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void Should_DelegateGetAllSpaces_When_CalledWithDataCenter() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);
        var expected = CompletableFuture.completedFuture(List.<ConfluenceSpace>of());
        when(dataCenterAdapter.getAllSpaces()).thenReturn(expected);

        var result = delegatingClient.getAllSpaces();

        assertThat(result).isSameAs(expected);
    }

    @Test
    void Should_DelegateGetAllPagesInSpace_When_CalledWithCloud() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);
        var expected = CompletableFuture.completedFuture(List.<ConfluencePage>of());
        when(cloudAdapter.getAllPagesInSpace("KEY")).thenReturn(expected);

        var result = delegatingClient.getAllPagesInSpace("KEY");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void Should_DelegateGetModifiedPagesSince_When_CalledWithDataCenter() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.DATA_CENTER);
        var since = Instant.now();
        var expected = CompletableFuture.completedFuture(List.<ModifiedPageInfo>of());
        when(dataCenterAdapter.getModifiedPagesSince("KEY", since)).thenReturn(expected);

        var result = delegatingClient.getModifiedPagesSince("KEY", since);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void Should_DelegateGetModifiedAttachmentsSince_When_CalledWithCloud() {
        when(config.deploymentType()).thenReturn(ConfluenceDeploymentType.CLOUD);
        var since = Instant.now();
        var expected = CompletableFuture.completedFuture(List.<ModifiedAttachmentInfo>of());
        when(cloudAdapter.getModifiedAttachmentsSince("KEY", since)).thenReturn(expected);

        var result = delegatingClient.getModifiedAttachmentsSince("KEY", since);

        assertThat(result).isSameAs(expected);
    }
}
