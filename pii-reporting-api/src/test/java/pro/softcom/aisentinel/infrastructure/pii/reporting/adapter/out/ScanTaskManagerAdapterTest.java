package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanTaskManagerAdapterTest {

    private ScanTaskManagerAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ScanTaskManagerAdapter();
    }

    @Test
    void Should_CancelPreviousJiraScan_When_NewJiraScanStarts() {
        // Arrange
        Flux<ContentScanResult> flux1 = Flux.never();
        Flux<ContentScanResult> flux2 = Flux.empty();
        adapter.startScan("scan1", SourceType.JIRA, flux1);
        assertThat(adapter.isScanActive("scan1")).isTrue();

        // Act
        adapter.startScan("scan2", SourceType.JIRA, flux2);

        // Assert
        assertThat(adapter.isScanActive("scan1")).isFalse();
    }

    @Test
    void Should_NotCancelConfluenceScan_When_NewJiraScanStarts() {
        // Arrange
        adapter.startScan("confluence-1", SourceType.CONFLUENCE, Flux.never());
        adapter.startScan("jira-1", SourceType.JIRA, Flux.never());

        // Act
        adapter.startScan("jira-2", SourceType.JIRA, Flux.empty());

        // Assert
        assertThat(adapter.isScanActive("confluence-1")).isTrue();
        assertThat(adapter.isScanActive("jira-1")).isFalse();
    }

    @Test
    void Should_AllowMultipleSourceTypes_When_ScansRunConcurrently() {
        // Arrange & Act
        adapter.startScan("confluence-1", SourceType.CONFLUENCE, Flux.never());
        adapter.startScan("jira-1", SourceType.JIRA, Flux.never());
        adapter.startScan("sharepoint-1", SourceType.SHAREPOINT, Flux.never());

        // Assert
        assertThat(adapter.isScanActive("confluence-1")).isTrue();
        assertThat(adapter.isScanActive("jira-1")).isTrue();
        assertThat(adapter.isScanActive("sharepoint-1")).isTrue();
    }

    @Test
    void Should_ThrowException_When_ScanIdIsNull() {
        final Flux<ContentScanResult> emptyScanResults = Flux.empty();
        assertThatThrownBy(() -> {
            adapter.startScan(null, SourceType.JIRA, emptyScanResults);
        })
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scanId cannot be null");
    }

    @Test
    void Should_ThrowException_When_FluxIsNull() {
        assertThatThrownBy(() -> adapter.startScan("scan-1", SourceType.JIRA, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scanDataStream cannot be null");
    }

    @Test
    void Should_HandleNullSourceType_When_StartingScan() {
        // Arrange & Act (should not throw)
        adapter.startScan("scan-1", null, Flux.never());

        // Assert - scan is registered and active
        assertThat(adapter.isScanActive("scan-1")).isTrue();
    }

    @Test
    void Should_PauseScan_When_ScanIsActive() {
        // Arrange
        adapter.startScan("scan-1", SourceType.JIRA, Flux.never());
        assertThat(adapter.isScanActive("scan-1")).isTrue();

        // Act
        boolean paused = adapter.pauseScan("scan-1");

        // Assert
        assertThat(paused).isTrue();
        assertThat(adapter.isScanActive("scan-1")).isFalse();
    }

    @Test
    void Should_ReturnFalse_When_PausingNonExistentScan() {
        assertThat(adapter.pauseScan("non-existent")).isFalse();
    }
}
