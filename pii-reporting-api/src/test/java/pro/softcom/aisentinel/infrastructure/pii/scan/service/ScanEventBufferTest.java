package pro.softcom.aisentinel.infrastructure.pii.scan.service;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.infrastructure.pii.scan.service.ScanEventBuffer.BufferedEvent;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScanEventBufferTest {

    private ScanEventBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = new ScanEventBuffer(5);
    }

    private ConfluenceContentScanResult buildEvent(String scanId) {
        return ConfluenceContentScanResult.builder()
                .scanId(scanId)
                .eventType("item")
                .emittedAt(Instant.now().toString())
                .build();
    }

    @Nested
    class AddEvent {

        @Test
        void Should_StoreEvent_When_BufferIsEmpty() {
            // Arrange
            ConfluenceContentScanResult event = buildEvent("scan-1");

            // Act
            buffer.addEvent("scan-1", 1L, event);

            // Assert
            List<BufferedEvent> events = buffer.getEventsAfter("scan-1", 0L);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).eventId()).isEqualTo(1L);
            assertThat(events.get(0).event()).isEqualTo(event);
        }

        @Test
        void Should_StoreMultipleEvents_When_SameScanId() {
            // Arrange
            ConfluenceContentScanResult event1 = buildEvent("scan-1");
            ConfluenceContentScanResult event2 = buildEvent("scan-1");
            ConfluenceContentScanResult event3 = buildEvent("scan-1");

            // Act
            buffer.addEvent("scan-1", 1L, event1);
            buffer.addEvent("scan-1", 2L, event2);
            buffer.addEvent("scan-1", 3L, event3);

            // Assert
            List<BufferedEvent> events = buffer.getEventsAfter("scan-1", 0L);
            assertThat(events).hasSize(3);
            assertThat(events).extracting(BufferedEvent::eventId)
                    .containsExactly(1L, 2L, 3L);
        }

        @Test
        void Should_CreateSeparateBuffers_When_DifferentScanIds() {
            // Arrange
            ConfluenceContentScanResult event1 = buildEvent("scan-1");
            ConfluenceContentScanResult event2 = buildEvent("scan-2");

            // Act
            buffer.addEvent("scan-1", 1L, event1);
            buffer.addEvent("scan-2", 2L, event2);

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(buffer.getEventsAfter("scan-1", 0L)).hasSize(1);
            softly.assertThat(buffer.getEventsAfter("scan-2", 0L)).hasSize(1);
            softly.assertThat(buffer.getEventsAfter("scan-1", 0L).get(0).eventId()).isEqualTo(1L);
            softly.assertThat(buffer.getEventsAfter("scan-2", 0L).get(0).eventId()).isEqualTo(2L);
            softly.assertAll();
        }

        @Test
        void Should_OverwriteOldestEvent_When_BufferCapacityExceeded() {
            // Arrange - buffer capacity is 5
            for (int i = 1; i <= 5; i++) {
                buffer.addEvent("scan-1", i, buildEvent("scan-1"));
            }

            // Act - add a 6th event, should overwrite the 1st
            buffer.addEvent("scan-1", 6L, buildEvent("scan-1"));

            // Assert
            List<BufferedEvent> events = buffer.getEventsAfter("scan-1", 0L);
            assertThat(events).hasSize(5);
            assertThat(events).extracting(BufferedEvent::eventId)
                    .containsExactly(2L, 3L, 4L, 5L, 6L);
        }
    }

    @Nested
    class GetEventsAfter {

        @Test
        void Should_ReturnEmptyList_When_NoBufferExistsForScanId() {
            // Act
            List<BufferedEvent> events = buffer.getEventsAfter("non-existent", 0L);

            // Assert
            assertThat(events).isEmpty();
        }

        @Test
        void Should_ReturnAllEvents_When_AfterEventIdIsZero() {
            // Arrange
            buffer.addEvent("scan-1", 1L, buildEvent("scan-1"));
            buffer.addEvent("scan-1", 2L, buildEvent("scan-1"));
            buffer.addEvent("scan-1", 3L, buildEvent("scan-1"));

            // Act
            List<BufferedEvent> events = buffer.getEventsAfter("scan-1", 0L);

            // Assert
            assertThat(events).hasSize(3);
            assertThat(events).extracting(BufferedEvent::eventId)
                    .containsExactly(1L, 2L, 3L);
        }

        @Test
        void Should_ReturnOnlyEventsAfterGivenId_When_PartialReplay() {
            // Arrange
            buffer.addEvent("scan-1", 1L, buildEvent("scan-1"));
            buffer.addEvent("scan-1", 2L, buildEvent("scan-1"));
            buffer.addEvent("scan-1", 3L, buildEvent("scan-1"));
            buffer.addEvent("scan-1", 4L, buildEvent("scan-1"));

            // Act
            List<BufferedEvent> events = buffer.getEventsAfter("scan-1", 2L);

            // Assert
            assertThat(events).hasSize(2);
            assertThat(events).extracting(BufferedEvent::eventId)
                    .containsExactly(3L, 4L);
        }

        @Test
        void Should_ReturnEmptyList_When_AfterEventIdIsLatest() {
            // Arrange
            buffer.addEvent("scan-1", 1L, buildEvent("scan-1"));
            buffer.addEvent("scan-1", 2L, buildEvent("scan-1"));

            // Act
            List<BufferedEvent> events = buffer.getEventsAfter("scan-1", 2L);

            // Assert
            assertThat(events).isEmpty();
        }

        @Test
        void Should_ReturnEmptyList_When_AfterEventIdExceedsAll() {
            // Arrange
            buffer.addEvent("scan-1", 1L, buildEvent("scan-1"));
            buffer.addEvent("scan-1", 2L, buildEvent("scan-1"));

            // Act
            List<BufferedEvent> events = buffer.getEventsAfter("scan-1", 100L);

            // Assert
            assertThat(events).isEmpty();
        }
    }

    @Nested
    class ClearBuffer {

        @Test
        void Should_RemoveBuffer_When_ScanIdExists() {
            // Arrange
            buffer.addEvent("scan-1", 1L, buildEvent("scan-1"));
            buffer.addEvent("scan-1", 2L, buildEvent("scan-1"));

            // Act
            buffer.clearBuffer("scan-1");

            // Assert
            assertThat(buffer.getEventsAfter("scan-1", 0L)).isEmpty();
        }

        @Test
        void Should_NotAffectOtherBuffers_When_ClearingSpecificScan() {
            // Arrange
            buffer.addEvent("scan-1", 1L, buildEvent("scan-1"));
            buffer.addEvent("scan-2", 2L, buildEvent("scan-2"));

            // Act
            buffer.clearBuffer("scan-1");

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(buffer.getEventsAfter("scan-1", 0L)).isEmpty();
            softly.assertThat(buffer.getEventsAfter("scan-2", 0L)).hasSize(1);
            softly.assertAll();
        }

        @Test
        void Should_NotThrow_When_ScanIdDoesNotExist() {
            // Act & Assert
            buffer.clearBuffer("non-existent");
            assertThat(buffer.getEventsAfter("non-existent", 0L)).isEmpty();
        }
    }

    @Nested
    class RingBufferTest {

        @Test
        void Should_ThrowIllegalArgumentException_When_CapacityIsZero() {
            assertThatThrownBy(() -> new ScanEventBuffer.RingBuffer<>(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Capacity must be positive");
        }

        @Test
        void Should_ThrowIllegalArgumentException_When_CapacityIsNegative() {
            assertThatThrownBy(() -> new ScanEventBuffer.RingBuffer<>(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Capacity must be positive");
        }

        @Test
        void Should_ReturnEmptyList_When_NoItemsAdded() {
            // Arrange
            ScanEventBuffer.RingBuffer<String> ring = new ScanEventBuffer.RingBuffer<>(3);

            // Act
            List<String> result = ring.stream();

            // Assert
            assertThat(result).isEmpty();
        }

        @Test
        void Should_ReturnItemsInOrder_When_BufferNotFull() {
            // Arrange
            ScanEventBuffer.RingBuffer<String> ring = new ScanEventBuffer.RingBuffer<>(5);
            ring.add("A");
            ring.add("B");
            ring.add("C");

            // Act
            List<String> result = ring.stream();

            // Assert
            assertThat(result).containsExactly("A", "B", "C");
        }

        @Test
        void Should_ReturnItemsInOrder_When_BufferExactlyFull() {
            // Arrange
            ScanEventBuffer.RingBuffer<String> ring = new ScanEventBuffer.RingBuffer<>(3);
            ring.add("A");
            ring.add("B");
            ring.add("C");

            // Act
            List<String> result = ring.stream();

            // Assert
            assertThat(result).containsExactly("A", "B", "C");
        }

        @Test
        void Should_OverwriteOldestAndMaintainOrder_When_BufferWrapsAround() {
            // Arrange
            ScanEventBuffer.RingBuffer<String> ring = new ScanEventBuffer.RingBuffer<>(3);
            ring.add("A");
            ring.add("B");
            ring.add("C");
            ring.add("D"); // overwrites A

            // Act
            List<String> result = ring.stream();

            // Assert
            assertThat(result).containsExactly("B", "C", "D");
        }

        @Test
        void Should_MaintainChronologicalOrder_When_MultipleWraparounds() {
            // Arrange
            ScanEventBuffer.RingBuffer<String> ring = new ScanEventBuffer.RingBuffer<>(3);
            ring.add("A");
            ring.add("B");
            ring.add("C");
            ring.add("D"); // overwrites A
            ring.add("E"); // overwrites B

            // Act
            List<String> result = ring.stream();

            // Assert
            assertThat(result).containsExactly("C", "D", "E");
        }

        @Test
        void Should_HandleSingleCapacity_When_CapacityIsOne() {
            // Arrange
            ScanEventBuffer.RingBuffer<String> ring = new ScanEventBuffer.RingBuffer<>(1);
            ring.add("A");
            ring.add("B");

            // Act
            List<String> result = ring.stream();

            // Assert
            assertThat(result).containsExactly("B");
        }

        @Test
        void Should_MaintainCorrectSize_When_FullWraparound() {
            // Arrange - capacity 3, add 7 items (more than 2 full cycles)
            ScanEventBuffer.RingBuffer<String> ring = new ScanEventBuffer.RingBuffer<>(3);
            ring.add("A");
            ring.add("B");
            ring.add("C");
            ring.add("D");
            ring.add("E");
            ring.add("F");
            ring.add("G");

            // Act
            List<String> result = ring.stream();

            // Assert
            assertThat(result)
                    .as("After 7 adds in a capacity-3 buffer, only last 3 items should remain")
                    .hasSize(3)
                    .containsExactly("E", "F", "G");
        }
    }

    @Nested
    class DefaultConstructor {

        @Test
        void Should_UseDefaultCapacity_When_NoArgConstructor() {
            // Arrange
            ScanEventBuffer defaultBuffer = new ScanEventBuffer();

            // Act - add more than a few events
            for (int i = 1; i <= 10; i++) {
                defaultBuffer.addEvent("scan-1", i, buildEvent("scan-1"));
            }

            // Assert
            List<BufferedEvent> events = defaultBuffer.getEventsAfter("scan-1", 0L);
            assertThat(events).hasSize(10);
        }
    }

    @Nested
    class BufferedEventRecord {

        @Test
        void Should_PreserveAllFields_When_Created() {
            // Arrange
            ConfluenceContentScanResult event = buildEvent("scan-1");
            Instant timestamp = Instant.now();

            // Act
            BufferedEvent bufferedEvent = new BufferedEvent(42L, event, timestamp);

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(bufferedEvent.eventId()).isEqualTo(42L);
            softly.assertThat(bufferedEvent.event()).isEqualTo(event);
            softly.assertThat(bufferedEvent.timestamp()).isEqualTo(timestamp);
            softly.assertAll();
        }
    }
}
