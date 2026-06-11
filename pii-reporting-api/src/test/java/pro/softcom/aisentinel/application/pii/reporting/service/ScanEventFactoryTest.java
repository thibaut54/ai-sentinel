package pro.softcom.aisentinel.application.pii.reporting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceUrlProvider;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.domain.confluence.AttachmentInfo;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.reporting.ConfluenceContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;
import pro.softcom.aisentinel.application.pii.reporting.service.parser.ContentParserFactory;
import pro.softcom.aisentinel.application.pii.reporting.service.parser.HtmlContentParser;
import pro.softcom.aisentinel.application.pii.reporting.service.parser.PlainTextParser;
import pro.softcom.aisentinel.domain.pii.reporting.PersonallyIdentifiableInformationSeverity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScanEventFactory")
class ScanEventFactoryTest {

    @Mock
    private ConfluenceUrlProvider confluenceUrlProvider;

    @Mock
    private SeverityCalculationService severityCalculationService;

    private ScanEventFactory factory;

    private static final String SCAN_ID = "scan-42";
    private static final String SPACE_KEY = "SPACE";

    @BeforeEach
    void setUp() {
        ContentParserFactory parserFactory = new ContentParserFactory(new PlainTextParser(), new HtmlContentParser());
        PiiContextExtractor piiContextExtractor = new PiiContextExtractor(parserFactory);
        factory = new ScanEventFactory(confluenceUrlProvider, piiContextExtractor, severityCalculationService);
        lenient().when(confluenceUrlProvider.pageUrl(anyString())).thenAnswer(inv -> "https://wiki/pages/" + inv.getArgument(0));
    }

    private ConfluencePage page(String id) {
        return ConfluencePage.builder().id(id).title("Page " + id).spaceKey(SPACE_KEY).build();
    }

    @Nested
    @DisplayName("createStartEvent")
    class CreateStartEvent {

        @Test
        @DisplayName("Should_ReturnStartEventWithRunningStatus_When_Called")
        void Should_ReturnStartEventWithRunningStatus_When_Called() {
            // Act
            ConfluenceContentScanResult result = factory.createStartEvent(SCAN_ID, SPACE_KEY, 10, 0.0);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.scanId()).isEqualTo(SCAN_ID);
                softly.assertThat(result.spaceKey()).isEqualTo(SPACE_KEY);
                softly.assertThat(result.eventType()).isEqualTo("start");
                softly.assertThat(result.pagesTotal()).isEqualTo(10);
                softly.assertThat(result.scanStatus()).isEqualTo(ScanStatus.RUNNING);
                softly.assertThat(result.analysisProgressPercentage()).isEqualTo(0.0);
                softly.assertThat(result.emittedAt()).isNotNull();
            });
        }
    }

    @Nested
    @DisplayName("createCompleteEvent")
    class CreateCompleteEvent {

        @Test
        @DisplayName("Should_ReturnCompleteEventWithCompletedStatus_When_Called")
        void Should_ReturnCompleteEventWithCompletedStatus_When_Called() {
            // Act
            ConfluenceContentScanResult result = factory.createCompleteEvent(SCAN_ID, SPACE_KEY);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.scanId()).isEqualTo(SCAN_ID);
                softly.assertThat(result.eventType()).isEqualTo("complete");
                softly.assertThat(result.scanStatus()).isEqualTo(ScanStatus.COMPLETED);
                softly.assertThat(result.analysisProgressPercentage()).isEqualTo(100.0);
            });
        }
    }

    @Nested
    @DisplayName("createPageStartEvent")
    class CreatePageStartEvent {

        @Test
        @DisplayName("Should_ReturnPageStartEventWithPageInfo_When_Called")
        void Should_ReturnPageStartEventWithPageInfo_When_Called() {
            // Arrange
            ConfluencePage page = page("p1");

            // Act
            ConfluenceContentScanResult result = factory.createPageStartEvent(SCAN_ID, SPACE_KEY, page, 0, 5, 0.0);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.eventType()).isEqualTo("pageStart");
                softly.assertThat(result.pageId()).isEqualTo("p1");
                softly.assertThat(result.pageTitle()).isEqualTo("Page p1");
                softly.assertThat(result.pageIndex()).isEqualTo(0);
                softly.assertThat(result.pagesTotal()).isEqualTo(5);
                softly.assertThat(result.pageUrl()).contains("p1");
                softly.assertThat(result.scanStatus()).isEqualTo(ScanStatus.RUNNING);
            });
        }
    }

    @Nested
    @DisplayName("createPageCompleteEvent")
    class CreatePageCompleteEvent {

        @Test
        @DisplayName("Should_ReturnPageCompleteEventWithPageInfo_When_Called")
        void Should_ReturnPageCompleteEventWithPageInfo_When_Called() {
            // Arrange
            ConfluencePage page = page("p2");

            // Act
            ConfluenceContentScanResult result = factory.createPageCompleteEvent(SCAN_ID, SPACE_KEY, page, 50.0);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.eventType()).isEqualTo("pageComplete");
                softly.assertThat(result.pageId()).isEqualTo("p2");
                softly.assertThat(result.analysisProgressPercentage()).isEqualTo(50.0);
                softly.assertThat(result.scanStatus()).isEqualTo(ScanStatus.RUNNING);
            });
        }
    }

    @Nested
    @DisplayName("createEmptyPageItemEvent")
    class CreateEmptyPageItemEvent {

        @Test
        @DisplayName("Should_ReturnItemEventWithEmptyPiiList_When_Called")
        void Should_ReturnItemEventWithEmptyPiiList_When_Called() {
            // Arrange
            ConfluencePage page = page("p3");

            // Act
            ConfluenceContentScanResult result = factory.createEmptyPageItemEvent(SCAN_ID, SPACE_KEY, page, 25.0);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.eventType()).isEqualTo("item");
                softly.assertThat(result.isFinal()).isTrue();
                softly.assertThat(result.detectedPIIList()).isEmpty();
                softly.assertThat(result.nbOfDetectedPIIBySeverity()).isEmpty();
                softly.assertThat(result.nbOfDetectedPIIByType()).isEmpty();
                softly.assertThat(result.severity()).isNull();
            });
        }
    }

    @Nested
    @DisplayName("createPageItemEvent")
    class CreatePageItemEvent {

        @Test
        @DisplayName("Should_ReturnItemEventWithEmptyPiiList_When_DetectionIsNull")
        void Should_ReturnItemEventWithEmptyPiiList_When_DetectionIsNull() {
            // Arrange
            ConfluencePage page = page("p4");

            // Act
            ConfluenceContentScanResult result = factory.createPageItemEvent(SCAN_ID, SPACE_KEY, page, "content", null, 40.0);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.eventType()).isEqualTo("item");
                softly.assertThat(result.detectedPIIList()).isEmpty();
                softly.assertThat(result.severity()).isNull();
                softly.assertThat(result.detectorRunStats()).isNull();
            });
        }

        @Test
        @DisplayName("Should_ReturnItemEventWithEmptyPiiList_When_DetectionHasNullSensitiveData")
        void Should_ReturnItemEventWithEmptyPiiList_When_DetectionHasNullSensitiveData() {
            // Arrange
            ConfluencePage page = page("p5");
            ContentPiiDetection detection = ContentPiiDetection.builder()
                    .pageId("p5")
                    .sensitiveDataFound(null)
                    .build();

            // Act
            ConfluenceContentScanResult result = factory.createPageItemEvent(SCAN_ID, SPACE_KEY, page, "content", detection, 50.0);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.detectedPIIList()).isEmpty();
                softly.assertThat(result.nbOfDetectedPIIBySeverity()).isEmpty();
            });
        }

        @Test
        @DisplayName("Should_ReturnItemEventWithPiiList_When_DetectionHasFindings")
        void Should_ReturnItemEventWithPiiList_When_DetectionHasFindings() {
            // Arrange
            ConfluencePage page = page("p6");
            String content = "Contact: john@example.com for info";
            int start = content.indexOf("john@example.com");
            int end = start + "john@example.com".length();
            ContentPiiDetection.SensitiveData sensitiveData = new ContentPiiDetection.SensitiveData(
                    "EMAIL", "Email", "john@example.com", null, start, end, 0.95,
                    null, ContentPiiDetection.DetectorSource.GLINER
            );
            ContentPiiDetection detection = ContentPiiDetection.builder()
                    .pageId("p6")
                    .sensitiveDataFound(List.of(sensitiveData))
                    .build();
            when(severityCalculationService.calculateSeverity("EMAIL"))
                    .thenReturn(PersonallyIdentifiableInformationSeverity.LOW);
            when(severityCalculationService.aggregateCounts(org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(new pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts(0, 0, 1));

            // Act
            ConfluenceContentScanResult result = factory.createPageItemEvent(SCAN_ID, SPACE_KEY, page, content, detection, 60.0);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.detectedPIIList()).hasSize(1);
                softly.assertThat(result.nbOfDetectedPIIByType()).containsKey("EMAIL");
                softly.assertThat(result.severity()).isEqualTo(PersonallyIdentifiableInformationSeverity.LOW);
            });
        }

        @Test
        @DisplayName("Should_ReturnHighestSeverity_When_MultipleDetectionsWithDifferentSeverities")
        void Should_ReturnHighestSeverity_When_MultipleDetectionsWithDifferentSeverities() {
            // Arrange
            ConfluencePage page = page("p7");
            String content = "Password: secret123 email: john@example.com";
            ContentPiiDetection.SensitiveData pwdData = new ContentPiiDetection.SensitiveData(
                    "PASSWORD", "Password", "secret123", null, 10, 19, 0.9,
                    null, ContentPiiDetection.DetectorSource.REGEX
            );
            ContentPiiDetection.SensitiveData emailData = new ContentPiiDetection.SensitiveData(
                    "EMAIL", "Email", "john@example.com", null, 27, 43, 0.95,
                    null, ContentPiiDetection.DetectorSource.GLINER
            );
            ContentPiiDetection detection = ContentPiiDetection.builder()
                    .pageId("p7")
                    .sensitiveDataFound(List.of(pwdData, emailData))
                    .build();
            when(severityCalculationService.calculateSeverity("PASSWORD"))
                    .thenReturn(PersonallyIdentifiableInformationSeverity.HIGH);
            when(severityCalculationService.calculateSeverity("EMAIL"))
                    .thenReturn(PersonallyIdentifiableInformationSeverity.LOW);
            when(severityCalculationService.aggregateCounts(org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(new pro.softcom.aisentinel.domain.pii.reporting.SeverityCounts(1, 0, 1));

            // Act
            ConfluenceContentScanResult result = factory.createPageItemEvent(SCAN_ID, SPACE_KEY, page, content, detection, 70.0);

            // Assert
            assertThat(result.severity()).isEqualTo(PersonallyIdentifiableInformationSeverity.HIGH);
        }
    }

    @Nested
    @DisplayName("createAttachmentItemEvent")
    class CreateAttachmentItemEvent {

        @Test
        @DisplayName("Should_ReturnAttachmentItemEvent_When_Called")
        void Should_ReturnAttachmentItemEvent_When_Called() {
            // Arrange
            ConfluencePage page = page("p8");
            AttachmentInfo attachment = new AttachmentInfo("file.pdf", "pdf", "application/pdf",
                    "https://wiki/attachments/file.pdf");

            // Act
            ConfluenceContentScanResult result = factory.createAttachmentItemEvent(
                    SCAN_ID, SPACE_KEY, page, attachment, "attachment content", null, 80.0);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.eventType()).isEqualTo("attachmentItem");
                softly.assertThat(result.isFinal()).isTrue();
                softly.assertThat(result.pageId()).isEqualTo("p8");
                softly.assertThat(result.attachmentName()).isEqualTo("file.pdf");
                softly.assertThat(result.attachmentType()).isEqualTo("application/pdf");
                softly.assertThat(result.attachmentUrl()).isEqualTo("https://wiki/attachments/file.pdf");
                softly.assertThat(result.detectedPIIList()).isEmpty();
            });
        }
    }

    @Nested
    @DisplayName("createErrorEvent")
    class CreateErrorEvent {

        @Test
        @DisplayName("Should_ReturnErrorEventWithFailedStatus_When_Called")
        void Should_ReturnErrorEventWithFailedStatus_When_Called() {
            // Act
            ConfluenceContentScanResult result = factory.createErrorEvent(
                    SCAN_ID, SPACE_KEY, "page-err", "Connection refused", 30.0);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.eventType()).isEqualTo("scanError");
                softly.assertThat(result.scanStatus()).isEqualTo(ScanStatus.FAILED);
                softly.assertThat(result.pageId()).isEqualTo("page-err");
                softly.assertThat(result.message()).isEqualTo("Connection refused");
                softly.assertThat(result.pageUrl()).contains("page-err");
            });
        }
    }

    @Nested
    @DisplayName("buildPageUrl - edge cases")
    class BuildPageUrl {

        @Test
        @DisplayName("Should_ReturnNull_When_ConfluenceUrlProviderIsNull")
        void Should_ReturnNull_When_ConfluenceUrlProviderIsNull() {
            // Arrange - factory without URL provider
            ContentParserFactory parserFactory = new ContentParserFactory(new PlainTextParser(), new HtmlContentParser());
            PiiContextExtractor extractor = new PiiContextExtractor(parserFactory);
            ScanEventFactory factoryNoUrl = new ScanEventFactory(null, extractor, severityCalculationService);

            // Act
            ConfluenceContentScanResult result = factoryNoUrl.createErrorEvent(SCAN_ID, SPACE_KEY, "p1", "error", 0.0);

            // Assert
            assertThat(result.pageUrl()).isNull();
        }

        @Test
        @DisplayName("Should_ReturnNull_When_PageIdIsNull")
        void Should_ReturnNull_When_PageIdIsNull() {
            // Act
            ConfluenceContentScanResult result = factory.createErrorEvent(SCAN_ID, SPACE_KEY, null, "error", 0.0);

            // Assert
            assertThat(result.pageUrl()).isNull();
        }
    }
}
