package pro.softcom.aisentinel.application.pii.reporting.service;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceUrlProvider;
import pro.softcom.aisentinel.application.jira.port.out.JiraUrlProvider;
import pro.softcom.aisentinel.application.pii.reporting.SeverityCalculationService;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage;
import pro.softcom.aisentinel.domain.jira.JiraIssue;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScanEventFactory}.
 * Focuses on content URL building logic for different ScannableContent types.
 */
@ExtendWith(MockitoExtension.class)
class ScanEventFactoryTest {

    @Mock
    private ConfluenceUrlProvider confluenceUrlProvider;

    @Mock
    private JiraUrlProvider jiraUrlProvider;

    @Mock
    private PiiContextExtractor piiContextExtractor;

    @Mock
    private SeverityCalculationService severityCalculationService;

    private ScanEventFactory scanEventFactory;

    @BeforeEach
    void setUp() {
        scanEventFactory = new ScanEventFactory(
            confluenceUrlProvider,
            jiraUrlProvider,
            piiContextExtractor,
            severityCalculationService
        );
    }

    @Nested
    class BuildContentUrlForJiraIssue {

        @Test
        void Should_BuildJiraUrl_When_ContentIsJiraIssue() {
            // Arrange
            JiraIssue jiraIssue = JiraIssue.builder()
                .id("10001")
                .key("PROJ-123")
                .projectKey("PROJ")
                .summary("Test issue")
                .build();

            when(jiraUrlProvider.issueUrl("PROJ-123"))
                .thenReturn("https://jira.example.com/browse/PROJ-123");

            // Act
            ContentScanResult result = scanEventFactory.createContentStartEvent(
                "scan-1", "PROJ", jiraIssue, 0, 10, 0.0
            );

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result.contentUrl())
                .as("Content URL should contain the Jira browse path with the issue key")
                .contains("/browse/PROJ-123");
            softly.assertThat(result.contentUrl())
                .as("Content URL should be the full Jira issue URL")
                .isEqualTo("https://jira.example.com/browse/PROJ-123");
            softly.assertAll();
        }

        @Test
        void Should_ReturnNullContentUrl_When_JiraUrlProviderReturnsNull() {
            // Arrange
            JiraIssue jiraIssue = JiraIssue.builder()
                .id("10001")
                .key("PROJ-123")
                .projectKey("PROJ")
                .summary("Test issue")
                .build();

            when(jiraUrlProvider.issueUrl("PROJ-123")).thenReturn(null);

            // Act
            ContentScanResult result = scanEventFactory.createContentStartEvent(
                "scan-1", "PROJ", jiraIssue, 0, 10, 0.0
            );

            // Assert
            assertThat(result.contentUrl())
                .as("Content URL should be null when JiraUrlProvider returns null")
                .isNull();
        }

        @Test
        void Should_UseIssueKey_When_BuildingJiraUrl() {
            // Arrange - use a different key to verify key() is used, not id()
            JiraIssue jiraIssue = JiraIssue.builder()
                .id("99999")
                .key("DATA-42")
                .projectKey("DATA")
                .summary("Key vs ID test")
                .build();

            when(jiraUrlProvider.issueUrl("DATA-42"))
                .thenReturn("https://jira.example.com/browse/DATA-42");

            // Act
            ContentScanResult result = scanEventFactory.createEmptyContentItemEvent(
                "scan-2", "DATA", jiraIssue, 50.0
            );

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result.contentUrl())
                .as("Content URL should use issue key (DATA-42) not numeric id (99999)")
                .contains("DATA-42");
            softly.assertThat(result.contentUrl())
                .as("Content URL should not contain the numeric ID")
                .doesNotContain("99999");
            softly.assertAll();
        }
    }

    @Nested
    class BuildContentUrlForConfluencePage {

        @Test
        void Should_BuildConfluenceUrl_When_ContentIsConfluencePage() {
            // Arrange
            ConfluencePage confluencePage = ConfluencePage.builder()
                .id("12345")
                .title("Test Page")
                .spaceKey("SPACE")
                .build();

            when(confluenceUrlProvider.pageUrl("12345"))
                .thenReturn("https://confluence.example.com/pages/viewpage.action?pageId=12345");

            // Act
            ContentScanResult result = scanEventFactory.createContentStartEvent(
                "scan-1", "SPACE", confluencePage, 0, 10, 0.0
            );

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result.contentUrl())
                .as("Content URL should contain the Confluence viewpage path")
                .contains("/pages/viewpage.action?pageId=12345");
            softly.assertThat(result.contentUrl())
                .as("Content URL should be the full Confluence page URL")
                .isEqualTo("https://confluence.example.com/pages/viewpage.action?pageId=12345");
            softly.assertAll();
        }

        @Test
        void Should_ReturnNullContentUrl_When_ConfluenceUrlProviderReturnsNull() {
            // Arrange
            ConfluencePage confluencePage = ConfluencePage.builder()
                .id("12345")
                .title("Test Page")
                .spaceKey("SPACE")
                .build();

            when(confluenceUrlProvider.pageUrl("12345")).thenReturn(null);

            // Act
            ContentScanResult result = scanEventFactory.createContentStartEvent(
                "scan-1", "SPACE", confluencePage, 0, 10, 0.0
            );

            // Assert
            assertThat(result.contentUrl())
                .as("Content URL should be null when ConfluenceUrlProvider returns null")
                .isNull();
        }
    }

    @Nested
    class BuildContentUrlForUnknownType {

        @Test
        void Should_ReturnNull_When_ContentIsUnknownType() {
            // Arrange - anonymous ScannableContent implementation (not Confluence, not Jira)
            ScannableContent unknownContent = new ScannableContent() {
                @Override
                public String getId() {
                    return "unknown-1";
                }

                @Override
                public String getContentBody() {
                    return "Some content";
                }

                @Override
                public String getTitle() {
                    return "Unknown Content";
                }

                @Override
                public String getSourceId() {
                    return "UNKNOWN";
                }

                @Override
                public Map<String, Object> getMetadata() {
                    return Map.of();
                }
            };

            // Act
            ContentScanResult result = scanEventFactory.createContentStartEvent(
                "scan-1", "UNKNOWN", unknownContent, 0, 10, 0.0
            );

            // Assert
            assertThat(result.contentUrl())
                .as("Content URL should be null for unknown ScannableContent types")
                .isNull();
        }

        @Test
        void Should_StillPopulateOtherFields_When_ContentUrlIsNull() {
            // Arrange
            ScannableContent unknownContent = new ScannableContent() {
                @Override
                public String getId() {
                    return "unknown-2";
                }

                @Override
                public String getContentBody() {
                    return "Body";
                }

                @Override
                public String getTitle() {
                    return "Some Title";
                }

                @Override
                public String getSourceId() {
                    return "SRC";
                }

                @Override
                public Map<String, Object> getMetadata() {
                    return Map.of();
                }
            };

            // Act
            ContentScanResult result = scanEventFactory.createContentStartEvent(
                "scan-3", "SRC", unknownContent, 1, 5, 20.0
            );

            // Assert
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(result.contentUrl())
                .as("Content URL should be null for unknown content type")
                .isNull();
            softly.assertThat(result.contentId())
                .as("Content ID should still be populated")
                .isEqualTo("unknown-2");
            softly.assertThat(result.contentTitle())
                .as("Content title should still be populated")
                .isEqualTo("Some Title");
            softly.assertThat(result.scanId())
                .as("Scan ID should still be populated")
                .isEqualTo("scan-3");
            softly.assertAll();
        }
    }
}
