package pro.softcom.aisentinel.application.sharepoint.usecase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.pii.reporting.port.out.PersonallyIdentifiableInformationScanExecutionOrchestratorPort;
import pro.softcom.aisentinel.application.pii.reporting.service.ContentScanOrchestrator;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;
import pro.softcom.aisentinel.application.sharepoint.service.SharePointAccessor;
import pro.softcom.aisentinel.application.sharepoint.service.SharePointTextExtractorPort;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamSharePointScanUseCaseTest {

    @Mock
    private SharePointAccessor sharePointAccessor;

    @Mock
    private SharePointTextExtractorPort textExtractor;

    @Mock
    private PiiDetectorClient piiDetectorClient;

    @Mock
    private ContentScanOrchestrator contentScanOrchestrator;

    @Mock
    private PersonallyIdentifiableInformationScanExecutionOrchestratorPort scanExecutionOrchestrator;

    private StreamSharePointScanUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new StreamSharePointScanUseCase(
                sharePointAccessor, textExtractor, piiDetectorClient,
                contentScanOrchestrator, scanExecutionOrchestrator
        );
    }

    @Test
    void Should_PurgePreviousDataAndStartScan_When_ScanAllSites() {
        // Arrange
        ContentScanResult mockResult = ContentScanResult.builder()
                .scanId("scan-1")
                .eventType("multiStart")
                .build();
        when(sharePointAccessor.getAllSites())
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        doNothing().when(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
        when(scanExecutionOrchestrator.subscribeScan(anyString())).thenReturn(Flux.just(mockResult));

        // Act
        Flux<ContentScanResult> result = useCase.scanAllSites();

        // Assert
        assertThat(result).isNotNull();
        verify(contentScanOrchestrator).purgePreviousScanData(SourceType.SHAREPOINT);
        verify(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
        verify(scanExecutionOrchestrator).subscribeScan(anyString());
    }

    @Test
    void Should_PurgeSelectedSitesDataAndStartScan_When_ScanSelectedSites() {
        // Arrange
        List<String> siteIds = List.of("site-1", "site-2");
        ContentScanResult mockResult = ContentScanResult.builder()
                .scanId("scan-1")
                .eventType("multiStart")
                .build();
        when(sharePointAccessor.getAllSites())
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        doNothing().when(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
        when(scanExecutionOrchestrator.subscribeScan(anyString())).thenReturn(Flux.just(mockResult));

        // Act
        Flux<ContentScanResult> result = useCase.scanSelectedSites(siteIds);

        // Assert
        assertThat(result).isNotNull();
        verify(contentScanOrchestrator).purgePreviousScanDataForSources(SourceType.SHAREPOINT, siteIds);
        verify(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
    }

    @Test
    void Should_GenerateUniqueScanId_When_ScanAllSitesCalled() {
        // Arrange
        ArgumentCaptor<String> scanIdCaptor = ArgumentCaptor.forClass(String.class);
        when(sharePointAccessor.getAllSites())
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        doNothing().when(scanExecutionOrchestrator).startScan(anyString(), any(SourceType.class), any(Flux.class));
        when(scanExecutionOrchestrator.subscribeScan(anyString())).thenReturn(Flux.empty());

        // Act
        useCase.scanAllSites();

        // Assert
        verify(scanExecutionOrchestrator).startScan(scanIdCaptor.capture(), any(SourceType.class), any(Flux.class));
        assertThat(scanIdCaptor.getValue())
                .as("Scan ID should be a valid UUID")
                .isNotBlank()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
