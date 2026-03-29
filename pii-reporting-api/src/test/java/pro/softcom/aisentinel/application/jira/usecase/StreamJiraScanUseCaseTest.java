package pro.softcom.aisentinel.application.jira.usecase;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.jira.service.JiraAccessor;
import pro.softcom.aisentinel.application.pii.reporting.port.out.PersonallyIdentifiableInformationScanExecutionOrchestratorPort;
import pro.softcom.aisentinel.application.pii.reporting.service.ContentScanOrchestrator;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StreamJiraScanUseCase}.
 * Verifies scan orchestration, project scanning, and error handling.
 */
@ExtendWith(MockitoExtension.class)
class StreamJiraScanUseCaseTest {

    @Mock
    private JiraAccessor jiraAccessor;

    @Mock
    private PiiDetectorClient piiDetectorClient;

    @Mock
    private ContentScanOrchestrator contentScanOrchestrator;

    @Mock
    private PersonallyIdentifiableInformationScanExecutionOrchestratorPort scanExecutionOrchestrator;

    private StreamJiraScanUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new StreamJiraScanUseCase(
            jiraAccessor, piiDetectorClient, contentScanOrchestrator, scanExecutionOrchestrator
        );
        // jiraAccessor.getAllProjects() is called eagerly during Flux construction,
        // so it must return a non-null CompletableFuture in every test.
        lenient().when(jiraAccessor.getAllProjects())
            .thenReturn(CompletableFuture.completedFuture(List.of()));
    }

    @Nested
    class ScanAllProjects {

        @Test
        void Should_StartScanAndSubscribe_When_ScanAllProjectsCalled() {
            // Arrange
            var resultFlux = Flux.just(createScanResult("scan-1", "MULTI_START"));
            doNothing().when(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
            when(scanExecutionOrchestrator.subscribeScan(anyString())).thenReturn(resultFlux);

            // Act
            Flux<ContentScanResult> result = useCase.scanAllProjects();

            // Assert
            assertThat(result)
                .as("scanAllProjects should return a non-null Flux")
                .isNotNull();
            verify(contentScanOrchestrator).purgePreviousScanData(SourceType.JIRA);
            verify(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
            verify(scanExecutionOrchestrator).subscribeScan(anyString());
        }

        @Test
        void Should_ReturnSubscribedFlux_When_ScanStartsSuccessfully() {
            // Arrange
            ContentScanResult expectedEvent = createScanResult("scan-1", "MULTI_START");
            doNothing().when(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
            when(scanExecutionOrchestrator.subscribeScan(anyString()))
                .thenReturn(Flux.just(expectedEvent));

            // Act
            Flux<ContentScanResult> result = useCase.scanAllProjects();

            // Assert
            StepVerifier.create(result)
                .assertNext(event -> {
                    SoftAssertions softly = new SoftAssertions();
                    softly.assertThat(event.scanId()).isEqualTo("scan-1");
                    softly.assertThat(event.eventType()).isEqualTo("MULTI_START");
                    softly.assertAll();
                })
                .verifyComplete();
        }

        @Test
        void Should_PassFluxToScanExecutionOrchestrator_When_ScanStarted() {
            // Arrange
            doNothing().when(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
            when(scanExecutionOrchestrator.subscribeScan(anyString())).thenReturn(Flux.empty());

            // Act
            useCase.scanAllProjects();

            // Assert
            ArgumentCaptor<String> scanIdCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Flux> fluxCaptor = ArgumentCaptor.forClass(Flux.class);
            verify(scanExecutionOrchestrator).startScan(scanIdCaptor.capture(), any(SourceType.class), fluxCaptor.capture());

            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(scanIdCaptor.getValue())
                .as("Scan ID should be a non-empty UUID string")
                .isNotBlank();
            softly.assertThat(fluxCaptor.getValue())
                .as("Flux passed to startScan should be non-null")
                .isNotNull();
            softly.assertAll();
        }
    }

    @Nested
    class ScanSelectedProjects {

        @Test
        void Should_PurgeOnlySelectedProjects_When_ScanSelectedProjectsCalled() {
            // Arrange
            List<String> projectKeys = List.of("PROJ1", "PROJ2");
            doNothing().when(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
            when(scanExecutionOrchestrator.subscribeScan(anyString())).thenReturn(Flux.empty());

            // Act
            useCase.scanSelectedProjects(projectKeys);

            // Assert
            verify(contentScanOrchestrator).purgePreviousScanDataForSources(SourceType.JIRA, projectKeys);
        }

        @Test
        void Should_ReturnSubscribedFlux_When_SelectedProjectsScanStarted() {
            // Arrange
            List<String> projectKeys = List.of("PROJ1");
            ContentScanResult expectedEvent = createScanResult("scan-2", "MULTI_START");
            doNothing().when(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
            when(scanExecutionOrchestrator.subscribeScan(anyString()))
                .thenReturn(Flux.just(expectedEvent));

            // Act
            Flux<ContentScanResult> result = useCase.scanSelectedProjects(projectKeys);

            // Assert
            StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();
        }

        @Test
        void Should_StartScanAndSubscribe_When_EmptyProjectKeysProvided() {
            // Arrange
            List<String> emptyKeys = List.of();
            doNothing().when(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
            when(scanExecutionOrchestrator.subscribeScan(anyString())).thenReturn(Flux.empty());

            // Act
            Flux<ContentScanResult> result = useCase.scanSelectedProjects(emptyKeys);

            // Assert
            assertThat(result)
                .as("scanSelectedProjects should return a non-null Flux even with empty keys")
                .isNotNull();
            verify(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
        }
    }

    // --- Helpers ---

    private ContentScanResult createScanResult(String scanId, String eventType) {
        return ContentScanResult.builder()
            .scanId(scanId)
            .eventType(eventType)
            .emittedAt(java.time.Instant.now().toString())
            .build();
    }
}
