package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.ScanNotFoundException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanTaskManagerAdapterTest {

    private ScanTaskManagerAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ScanTaskManagerAdapter();
    }

    private ConfluenceContentScanResult buildResult(String scanId, String eventType) {
        return ConfluenceContentScanResult.builder()
                .scanId(scanId)
                .spaceKey("SPACE")
                .eventType(eventType)
                .build();
    }

    @Test
    void Should_ThrowIllegalArgument_When_StartScanWithNullScanId() {
        Flux<ConfluenceContentScanResult> emptyStream = Flux.empty();
        assertThatThrownBy(() -> adapter.startScan(null, emptyStream))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanId cannot be null");
    }

    @Test
    void Should_ThrowIllegalArgument_When_StartScanWithNullDataStream() {
        assertThatThrownBy(() -> adapter.startScan("scan-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scanDataStream cannot be null");
    }

    @Test
    void Should_ReturnErrorFlux_When_SubscribeToUnknownScan() {
        Flux<ConfluenceContentScanResult> result = adapter.subscribeScan("unknown-scan");

        StepVerifier.create(result)
                .expectError(ScanNotFoundException.class)
                .verify();
    }

    @Test
    void Should_ReturnFalse_When_PauseUnknownScan() {
        boolean paused = adapter.pauseScan("unknown-scan");

        assertThat(paused).isFalse();
    }

    @Test
    void Should_EmitEvents_When_ScanStartedAndSubscribed() {
        // Arrange
        String scanId = "scan-emit-test";
        ConfluenceContentScanResult event = buildResult(scanId, "PAGE");
        Flux<ConfluenceContentScanResult> source = Flux.just(event);

        // Act
        adapter.startScan(scanId, source);
        Flux<ConfluenceContentScanResult> subscription = adapter.subscribeScan(scanId);

        // Assert
        StepVerifier.create(subscription)
                .expectNext(event)
                .expectComplete()
                .verify();
    }

    @Test
    void Should_ReturnTrue_When_PauseActiveScan() {
        // Arrange: a never-ending source so the subscription stays active until paused
        String scanId = "scan-pause-test";
        Flux<ConfluenceContentScanResult> neverEndingSource = Flux.never();

        // Act
        adapter.startScan(scanId, neverEndingSource);
        boolean paused = adapter.pauseScan(scanId);

        // Assert
        assertThat(paused).isTrue();
    }

    @Test
    void Should_ReturnFalse_When_PauseAlreadyDisposedScan() {
        // Arrange: a never-ending scan that is disposed only by our explicit first pause,
        // so the "already disposed" state is reached deterministically (no async wait).
        String scanId = "scan-already-disposed";
        Flux<ConfluenceContentScanResult> source = Flux.never();

        adapter.startScan(scanId, source);
        boolean firstPause = adapter.pauseScan(scanId); // disposes the subscription

        // Act - second pause sees the subscription already disposed
        boolean secondPause = adapter.pauseScan(scanId);

        // Assert
        assertThat(firstPause).isTrue();
        assertThat(secondPause).isFalse();
    }

    @Test
    void Should_DoNothing_When_CleanupCalledWithNoScans() {
        assertThatCode(() -> adapter.cleanupCompletedScans())
                .doesNotThrowAnyException();
    }

    @Test
    void Should_RegisterScan_When_StartScanCalled() {
        // Arrange
        String scanId = "scan-register";
        Flux<ConfluenceContentScanResult> source = Flux.never();

        // Act
        adapter.startScan(scanId, source);

        // Assert - subscription does not throw, scan is registered
        Flux<ConfluenceContentScanResult> sub = adapter.subscribeScan(scanId);
        assertThat(sub).isNotNull();

        adapter.pauseScan(scanId);
    }
}
