package pro.softcom.aisentinel.infrastructure.pii.scan.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanEventBufferTest {

    private ScanEventBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new ScanEventBuffer(10);
    }

    private ConfluenceContentScanResult buildResult(String scanId, String eventType) {
        return ConfluenceContentScanResult.builder()
                .scanId(scanId)
                .eventType(eventType)
                .spaceKey("SPACE")
                .build();
    }

    @Test
    void Should_ReturnEmptyList_When_NoEventsAddedForScan() {
        List<ScanEventBuffer.BufferedEvent> events = buffer.getEventsAfter("unknown-scan", 0);

        assertThat(events).isEmpty();
    }

    @Test
    void Should_ReturnEventsAfterGivenId_When_EventsAdded() {
        // Arrange
        String scanId = "scan-1";
        ConfluenceContentScanResult evt1 = buildResult(scanId, "PAGE");
        ConfluenceContentScanResult evt2 = buildResult(scanId, "COMPLETE");
        buffer.addEvent(scanId, 1, evt1);
        buffer.addEvent(scanId, 2, evt2);

        // Act
        List<ScanEventBuffer.BufferedEvent> events = buffer.getEventsAfter(scanId, 1);

        // Assert
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().eventId()).isEqualTo(2);
    }

    @Test
    void Should_ReturnAllEvents_When_AfterIdIsZero() {
        // Arrange
        String scanId = "scan-all";
        buffer.addEvent(scanId, 1, buildResult(scanId, "PAGE"));
        buffer.addEvent(scanId, 2, buildResult(scanId, "COMPLETE"));

        // Act
        List<ScanEventBuffer.BufferedEvent> events = buffer.getEventsAfter(scanId, 0);

        // Assert
        assertThat(events).hasSize(2);
    }

    @Test
    void Should_ReturnEmptyList_When_ClearBufferCalledAndThenQueried() {
        // Arrange
        String scanId = "scan-clear";
        buffer.addEvent(scanId, 1, buildResult(scanId, "PAGE"));

        // Act
        buffer.clearBuffer(scanId);
        List<ScanEventBuffer.BufferedEvent> events = buffer.getEventsAfter(scanId, 0);

        // Assert
        assertThat(events).isEmpty();
    }

    @Test
    void Should_DoNothing_When_ClearBufferCalledForUnknownScan() {
        assertThatCode(() -> buffer.clearBuffer("non-existent-scan")).doesNotThrowAnyException();
    }

    @Test
    void Should_OverwriteOldestEvents_When_BufferCapacityExceeded() {
        // Arrange
        ScanEventBuffer smallBuffer = new ScanEventBuffer(3);
        String scanId = "scan-overflow";

        // Add 4 events in a buffer of capacity 3
        for (int i = 1; i <= 4; i++) {
            smallBuffer.addEvent(scanId, i, buildResult(scanId, "E" + i));
        }

        // Act
        List<ScanEventBuffer.BufferedEvent> events = smallBuffer.getEventsAfter(scanId, 0);

        // Assert - only last 3 events should remain
        assertThat(events).hasSize(3);
        List<Long> eventIds = events.stream().map(ScanEventBuffer.BufferedEvent::eventId).toList();
        assertThat(eventIds).containsExactly(2L, 3L, 4L);
    }

    @Test
    void Should_UseDefaultCapacity_When_DefaultConstructorUsed() {
        ScanEventBuffer defaultBuffer = new ScanEventBuffer();
        String scanId = "scan-default";

        defaultBuffer.addEvent(scanId, 1, buildResult(scanId, "PAGE"));
        List<ScanEventBuffer.BufferedEvent> events = defaultBuffer.getEventsAfter(scanId, 0);

        assertThat(events).hasSize(1);
    }

    @Test
    void Should_ThrowIllegalArgument_When_RingBufferCreatedWithNonPositiveCapacity() {
        assertThatThrownBy(() -> new ScanEventBuffer.RingBuffer<>(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Capacity must be positive");
    }
}
