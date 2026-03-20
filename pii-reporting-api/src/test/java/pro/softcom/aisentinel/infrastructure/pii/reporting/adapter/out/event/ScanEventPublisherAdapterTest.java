package pro.softcom.aisentinel.infrastructure.pii.reporting.adapter.out.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.scan.SpaceScanCompleted;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScanEventPublisherAdapterTest {

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private ScanEventPublisherAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ScanEventPublisherAdapter(applicationEventPublisher);
    }

    @Test
    void Should_PublishEvent_When_ValidEventProvided() {
        // Arrange
        SpaceScanCompleted event = new SpaceScanCompleted("scan-1", "SPACE1", SourceType.CONFLUENCE);

        // Act
        adapter.publishCompleteEvent(event);

        // Assert
        verify(applicationEventPublisher).publishEvent(event);
    }

    @Test
    void Should_PublishSharePointEvent_When_SharePointSourceType() {
        // Arrange
        SpaceScanCompleted event = new SpaceScanCompleted("scan-2", "site-123", SourceType.SHAREPOINT);

        // Act
        adapter.publishCompleteEvent(event);

        // Assert
        verify(applicationEventPublisher).publishEvent(event);
    }

    @Test
    void Should_ThrowException_When_EventIsNull() {
        // Act & Assert
        assertThatThrownBy(() -> adapter.publishCompleteEvent(null))
                .isInstanceOf(NullPointerException.class);
    }
}
