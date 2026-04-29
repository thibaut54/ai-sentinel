package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.ScanNotFoundException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanTaskManagerAdapterTest {

    private ScanTaskManagerAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ScanTaskManagerAdapter();
    }

    private ConfluenceContentScanResult buildEvent(String scanId, String eventType) {
        return ConfluenceContentScanResult.builder()
                .scanId(scanId)
                .eventType(eventType)
                .emittedAt(Instant.now().toString())
                .build();
    }

    /**
     * Deterministically wait until the scan is marked completed.
     *
     * <p>Replaces the previous {@code Thread.sleep(200)} which was flaky on CI
     * runners under load: the boundedElastic scheduler may not have yet drained
     * the empty Flux subscription before the test hands off to cleanup. We poll
     * the {@code isCompleted} AtomicBoolean exposed by the ManagedScan record
     * via reflection until it flips, with a generous 5s timeout.</p>
     */
    private static void awaitScanCompleted(ScanTaskManagerAdapter adapter, String scanId)
            throws Exception {
        java.lang.reflect.Field managedScansField =
                ScanTaskManagerAdapter.class.getDeclaredField("managedScans");
        managedScansField.setAccessible(true);
        java.util.Map<?, ?> scans = (java.util.Map<?, ?>) managedScansField.get(adapter);

        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            Object scan = scans.get(scanId);
            if (scan != null) {
                java.lang.reflect.Method isCompletedMethod =
                        scan.getClass().getMethod("isCompleted");
                Object isCompleted = isCompletedMethod.invoke(scan);
                if (isCompleted instanceof java.util.concurrent.atomic.AtomicBoolean ab && ab.get()) {
                    return;
                }
            }
            // LockSupport.parkNanos avoids Sonar S2925 (Thread.sleep) and is a
            // canonical primitive for short test polling waits.
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("Scan " + scanId + " did not complete within 5 seconds");
    }

    @Nested
    class StartScan {

        @Test
        void Should_ThrowIllegalArgumentException_When_ScanIdIsNull() {
            // Arrange
            Flux<ConfluenceContentScanResult> stream = Flux.empty();

            // Act & Assert
            assertThatThrownBy(() -> adapter.startScan(null, stream))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scanId cannot be null");
        }

        @Test
        void Should_ThrowIllegalArgumentException_When_ScanDataStreamIsNull() {
            // Act & Assert
            assertThatThrownBy(() -> adapter.startScan("scan-1", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("scanDataStream cannot be null");
        }

        @Test
        void Should_RegisterScan_When_ValidArguments() {
            // Arrange
            Flux<ConfluenceContentScanResult> stream = Flux.empty();

            // Act
            adapter.startScan("scan-1", stream);

            // Assert - subscribeScan should not throw ScanNotFoundException
            Flux<ConfluenceContentScanResult> result = adapter.subscribeScan("scan-1");
            assertThat(result).isNotNull();
        }
    }

    @Nested
    class SubscribeScan {

        @Test
        void Should_ReturnErrorFlux_When_ScanIdIsUnknown() {
            // Act
            Flux<ConfluenceContentScanResult> result = adapter.subscribeScan("unknown-scan");

            // Assert
            StepVerifier.create(result)
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(ScanNotFoundException.class);
                        assertThat(error.getMessage()).contains("unknown-scan");
                    })
                    .verify(Duration.ofSeconds(2));
        }

        @Test
        void Should_ReceiveEvents_When_ScanEmitsData() {
            // Arrange
            Sinks.Many<ConfluenceContentScanResult> source = Sinks.many().unicast().onBackpressureBuffer();
            Flux<ConfluenceContentScanResult> scanStream = source.asFlux();

            ConfluenceContentScanResult event1 = buildEvent("scan-1", "item");
            ConfluenceContentScanResult event2 = buildEvent("scan-1", "item");

            adapter.startScan("scan-1", scanStream);

            // Act
            Flux<ConfluenceContentScanResult> subscription = adapter.subscribeScan("scan-1");

            // Emit events after a small delay to allow subscription wiring
            source.tryEmitNext(event1);
            source.tryEmitNext(event2);
            source.tryEmitComplete();

            // Assert
            StepVerifier.create(subscription)
                    .expectNext(event1)
                    .expectNext(event2)
                    .verifyComplete();
        }

        @Test
        void Should_ReceiveReplayedEvents_When_SubscribingAfterEmission() throws Exception {
            // Arrange
            Sinks.Many<ConfluenceContentScanResult> source = Sinks.many().unicast().onBackpressureBuffer();
            Flux<ConfluenceContentScanResult> scanStream = source.asFlux();

            ConfluenceContentScanResult event1 = buildEvent("scan-1", "item");

            adapter.startScan("scan-1", scanStream);

            // Emit event before subscribing
            source.tryEmitNext(event1);
            source.tryEmitComplete();

            // Wait for completion deterministically (was: flaky Thread.sleep(200))
            awaitScanCompleted(adapter, "scan-1");

            // Act & Assert - late subscriber should still receive replayed events
            Flux<ConfluenceContentScanResult> subscription = adapter.subscribeScan("scan-1");

            StepVerifier.create(subscription)
                    .expectNext(event1)
                    .verifyComplete();
        }
    }

    @Nested
    class PauseScan {

        @Test
        void Should_ReturnFalse_When_ScanIdIsUnknown() {
            // Act
            boolean result = adapter.pauseScan("unknown-scan");

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        void Should_ReturnFalse_When_SubscriptionAlreadyDisposed() throws Exception {
            // Arrange
            Flux<ConfluenceContentScanResult> stream = Flux.empty();
            adapter.startScan("scan-1", stream);

            // Wait for completion deterministically (was: flaky Thread.sleep(200))
            awaitScanCompleted(adapter, "scan-1");

            // Act
            boolean result = adapter.pauseScan("scan-1");

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        void Should_ReturnTrueAndDispose_When_ScanIsActive() {
            // Arrange - use a never-ending flux to keep the scan active
            Sinks.Many<ConfluenceContentScanResult> source = Sinks.many().unicast().onBackpressureBuffer();
            adapter.startScan("scan-1", source.asFlux());

            // Act
            boolean result = adapter.pauseScan("scan-1");

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        void Should_ReturnFalseOnSecondPause_When_AlreadyPaused() {
            // Arrange - use a never-ending flux to keep the scan active
            Sinks.Many<ConfluenceContentScanResult> source = Sinks.many().unicast().onBackpressureBuffer();
            adapter.startScan("scan-1", source.asFlux());

            // Act - first pause succeeds
            boolean firstPause = adapter.pauseScan("scan-1");
            // Second pause should fail (already disposed)
            boolean secondPause = adapter.pauseScan("scan-1");

            // Assert
            assertThat(firstPause).isTrue();
            assertThat(secondPause).isFalse();
        }
    }
}
