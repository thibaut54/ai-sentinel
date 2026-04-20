package pro.softcom.aisentinel.application.pii.reporting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceUrlProvider;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.application.pii.reporting.usecase.DetectionReportingEventType;
import pro.softcom.aisentinel.domain.confluence.AttachmentInfo;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScanEventFactory} focused on URL routing between events.
 *
 * <p>Business intent: guarantee that each event type uses the <em>correct</em> URL port method.
 * In particular, {@code ATTACHMENT_ITEM} must use {@code attachmentsUrl} (listing URL)
 * while all page-level events must use {@code pageUrl} (canonical page URL).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScanEventFactory - URL routing per event type")
class ScanEventFactoryTest {

    private static final String SCAN_ID = "scan-1";
    private static final String SPACE_KEY = "TEAM";
    private static final String PAGE_ID = "page-42";
    private static final String PAGE_TITLE = "My Page";

    private static final String PAGE_URL = "https://example.atlassian.net/wiki/spaces/TEAM/pages/page-42";
    private static final String ATTACHMENTS_URL = "https://example.atlassian.net/wiki/spaces/listattachmentsforspace.action?key=TEAM";

    @Mock
    private ConfluenceUrlProvider confluenceUrlProvider;

    @Mock
    private PiiContextExtractor piiContextExtractor;

    @Mock
    private SeverityCalculationService severityCalculationService;

    private ScanEventFactory scanEventFactory;

    @BeforeEach
    void setUp() {
        scanEventFactory = new ScanEventFactory(confluenceUrlProvider, piiContextExtractor, severityCalculationService);
    }

    @Test
    @DisplayName("ATTACHMENT_ITEM event uses attachmentsUrl(spaceKey, pageId) and NOT pageUrl")
    void Should_UseAttachmentsUrl_When_EventTypeIsAttachmentItem() {
        ConfluencePage page = aPage();
        AttachmentInfo attachment = new AttachmentInfo("file.pdf", "pdf", "application/pdf", "https://files/file.pdf");
        ContentPiiDetection detection = emptyDetection();
        when(confluenceUrlProvider.attachmentsUrl(SPACE_KEY, PAGE_ID)).thenReturn(ATTACHMENTS_URL);

        ConfluenceContentScanResult event = scanEventFactory.createAttachmentItemEvent(
            SCAN_ID, SPACE_KEY, page, attachment, "content", detection, 10.0);

        assertThat(event.eventType()).isEqualTo(DetectionReportingEventType.ATTACHMENT_ITEM.getLabel());
        assertThat(event.pageUrl())
            .as("pageUrl on ATTACHMENT_ITEM must be the attachments listing URL")
            .isEqualTo(ATTACHMENTS_URL);
        verify(confluenceUrlProvider).attachmentsUrl(SPACE_KEY, PAGE_ID);
        verifyNoMoreInteractions(confluenceUrlProvider);
    }

    @Test
    @DisplayName("PAGE_START event uses pageUrl(spaceKey, pageId)")
    void Should_UsePageUrl_When_EventTypeIsPageStart() {
        ConfluencePage page = aPage();
        when(confluenceUrlProvider.pageUrl(SPACE_KEY, PAGE_ID)).thenReturn(PAGE_URL);

        ConfluenceContentScanResult event = scanEventFactory.createPageStartEvent(
            SCAN_ID, SPACE_KEY, page, 0, 1, 5.0);

        assertThat(event.eventType()).isEqualTo(DetectionReportingEventType.PAGE_START.getLabel());
        assertThat(event.pageUrl()).isEqualTo(PAGE_URL);
        verify(confluenceUrlProvider).pageUrl(SPACE_KEY, PAGE_ID);
        verifyNoMoreInteractions(confluenceUrlProvider);
    }

    @Test
    @DisplayName("PAGE_COMPLETE event uses pageUrl(spaceKey, pageId)")
    void Should_UsePageUrl_When_EventTypeIsPageComplete() {
        ConfluencePage page = aPage();
        when(confluenceUrlProvider.pageUrl(SPACE_KEY, PAGE_ID)).thenReturn(PAGE_URL);

        ConfluenceContentScanResult event = scanEventFactory.createPageCompleteEvent(
            SCAN_ID, SPACE_KEY, page, 100.0);

        assertThat(event.eventType()).isEqualTo(DetectionReportingEventType.PAGE_COMPLETE.getLabel());
        assertThat(event.pageUrl()).isEqualTo(PAGE_URL);
        verify(confluenceUrlProvider).pageUrl(SPACE_KEY, PAGE_ID);
        verifyNoMoreInteractions(confluenceUrlProvider);
    }

    @Test
    @DisplayName("Empty ITEM event uses pageUrl(spaceKey, pageId)")
    void Should_UsePageUrl_When_EventTypeIsEmptyItem() {
        ConfluencePage page = aPage();
        when(confluenceUrlProvider.pageUrl(SPACE_KEY, PAGE_ID)).thenReturn(PAGE_URL);

        ConfluenceContentScanResult event = scanEventFactory.createEmptyPageItemEvent(
            SCAN_ID, SPACE_KEY, page, 50.0);

        assertThat(event.eventType()).isEqualTo(DetectionReportingEventType.ITEM.getLabel());
        assertThat(event.pageUrl()).isEqualTo(PAGE_URL);
        verify(confluenceUrlProvider).pageUrl(SPACE_KEY, PAGE_ID);
        verifyNoMoreInteractions(confluenceUrlProvider);
    }

    @Test
    @DisplayName("Non-empty ITEM event uses pageUrl(spaceKey, pageId) and NOT attachmentsUrl")
    void Should_UsePageUrl_When_EventTypeIsPageItem() {
        ConfluencePage page = aPage();
        ContentPiiDetection detection = emptyDetection();
        when(confluenceUrlProvider.pageUrl(SPACE_KEY, PAGE_ID)).thenReturn(PAGE_URL);

        ConfluenceContentScanResult event = scanEventFactory.createPageItemEvent(
            SCAN_ID, SPACE_KEY, page, "content", detection, 80.0);

        assertThat(event.eventType()).isEqualTo(DetectionReportingEventType.ITEM.getLabel());
        assertThat(event.pageUrl()).isEqualTo(PAGE_URL);
        verify(confluenceUrlProvider).pageUrl(SPACE_KEY, PAGE_ID);
        verifyNoMoreInteractions(confluenceUrlProvider);
    }

    @Test
    @DisplayName("ERROR event uses pageUrl(spaceKey, pageId)")
    void Should_UsePageUrl_When_EventTypeIsError() {
        when(confluenceUrlProvider.pageUrl(SPACE_KEY, PAGE_ID)).thenReturn(PAGE_URL);

        ConfluenceContentScanResult event = scanEventFactory.createErrorEvent(
            SCAN_ID, SPACE_KEY, PAGE_ID, "boom", 75.0);

        assertThat(event.eventType()).isEqualTo(DetectionReportingEventType.ERROR.getLabel());
        assertThat(event.pageUrl()).isEqualTo(PAGE_URL);
        verify(confluenceUrlProvider).pageUrl(SPACE_KEY, PAGE_ID);
        verifyNoMoreInteractions(confluenceUrlProvider);
    }

    private static ConfluencePage aPage() {
        return ConfluencePage.builder()
            .id(PAGE_ID)
            .title(PAGE_TITLE)
            .spaceKey(SPACE_KEY)
            .content(new ConfluencePage.HtmlContent("body"))
            .build();
    }

    private static ContentPiiDetection emptyDetection() {
        return ContentPiiDetection.builder()
            .sensitiveDataFound(List.of())
            .statistics(Map.of())
            .build();
    }
}
