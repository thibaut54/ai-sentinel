package pro.softcom.aisentinel.domain.pii.reporting;

import org.junit.jupiter.api.Test;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage;
import pro.softcom.aisentinel.domain.pii.ScanStatus;
import pro.softcom.aisentinel.domain.pii.export.SourceType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScanRemainingPagesCalculatorTest {

    @Test
    void Should_ReturnAllPages_When_CheckpointIsNull() {
        // Arrange
        List<ConfluencePage> pages = List.of(
                createPage("p1"), createPage("p2"), createPage("p3")
        );

        // Act
        ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, null);

        // Assert
        assertThat(result.originalTotal()).isEqualTo(3);
        assertThat(result.analyzedOffset()).isZero();
        assertThat(result.remaining()).hasSize(3);
    }

    @Test
    void Should_ReturnRemainingPages_When_CheckpointHasLastProcessedPage() {
        // Arrange
        List<ConfluencePage> pages = List.of(
                createPage("p1"), createPage("p2"), createPage("p3")
        );
        ScanCheckpoint checkpoint = ScanCheckpoint.builder()
                .scanId("scan-1")
                .sourceType(SourceType.CONFLUENCE)
                .sourceKey("SPACE1")
                .lastProcessedContentId("p2")
                .scanStatus(ScanStatus.PAUSED)
                .build();

        // Act
        ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, checkpoint);

        // Assert
        assertThat(result.originalTotal()).isEqualTo(3);
        assertThat(result.analyzedOffset()).isEqualTo(2);
        assertThat(result.remaining()).hasSize(1);
        assertThat(result.remaining().get(0).id()).isEqualTo("p3");
    }

    @Test
    void Should_ReturnEmptyList_When_PagesIsNull() {
        // Arrange / Act
        ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(null, null);

        // Assert
        assertThat(result.originalTotal()).isZero();
        assertThat(result.analyzedOffset()).isZero();
        assertThat(result.remaining()).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_PagesIsEmpty() {
        // Arrange / Act
        ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(List.of(), null);

        // Assert
        assertThat(result.originalTotal()).isZero();
        assertThat(result.remaining()).isEmpty();
    }

    @Test
    void Should_ReturnEmptyList_When_CheckpointIsCompleted() {
        // Arrange
        List<ConfluencePage> pages = List.of(createPage("p1"), createPage("p2"));
        ScanCheckpoint checkpoint = ScanCheckpoint.builder()
                .scanId("scan-1")
                .sourceType(SourceType.CONFLUENCE)
                .sourceKey("SPACE1")
                .lastProcessedContentId("p2")
                .scanStatus(ScanStatus.COMPLETED)
                .build();

        // Act
        ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, checkpoint);

        // Assert
        assertThat(result.remaining()).isEmpty();
    }

    @Test
    void Should_ReturnAllPages_When_LastProcessedPageNotFound() {
        // Arrange
        List<ConfluencePage> pages = List.of(createPage("p1"), createPage("p2"));
        ScanCheckpoint checkpoint = ScanCheckpoint.builder()
                .scanId("scan-1")
                .sourceType(SourceType.CONFLUENCE)
                .sourceKey("SPACE1")
                .lastProcessedContentId("unknown-page")
                .scanStatus(ScanStatus.RUNNING)
                .build();

        // Act
        ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, checkpoint);

        // Assert
        assertThat(result.remaining()).hasSize(2);
        assertThat(result.analyzedOffset()).isZero();
    }

    @Test
    void Should_IncludeCurrentPage_When_AttachmentWasInProgress() {
        // Arrange
        List<ConfluencePage> pages = List.of(
                createPage("p1"), createPage("p2"), createPage("p3")
        );
        ScanCheckpoint checkpoint = ScanCheckpoint.builder()
                .scanId("scan-1")
                .sourceType(SourceType.CONFLUENCE)
                .sourceKey("SPACE1")
                .lastProcessedContentId("p2")
                .lastProcessedAttachmentName("file.pdf")
                .scanStatus(ScanStatus.RUNNING)
                .build();

        // Act
        ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, checkpoint);

        // Assert
        assertThat(result.remaining()).hasSize(2);
        assertThat(result.remaining().get(0).id()).isEqualTo("p2");
        assertThat(result.analyzedOffset())
                .as("Offset should be decremented by 1 when attachment was in progress")
                .isEqualTo(1);
    }

    @Test
    void Should_ReturnAllPages_When_LastProcessedContentIdIsBlank() {
        // Arrange
        List<ConfluencePage> pages = List.of(createPage("p1"), createPage("p2"));
        ScanCheckpoint checkpoint = ScanCheckpoint.builder()
                .scanId("scan-1")
                .sourceType(SourceType.CONFLUENCE)
                .sourceKey("SPACE1")
                .lastProcessedContentId("")
                .scanStatus(ScanStatus.RUNNING)
                .build();

        // Act
        ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, checkpoint);

        // Assert
        assertThat(result.remaining()).hasSize(2);
    }

    @Test
    void Should_ReturnEmptyList_When_LastPageProcessed() {
        // Arrange
        List<ConfluencePage> pages = List.of(createPage("p1"), createPage("p2"));
        ScanCheckpoint checkpoint = ScanCheckpoint.builder()
                .scanId("scan-1")
                .sourceType(SourceType.CONFLUENCE)
                .sourceKey("SPACE1")
                .lastProcessedContentId("p2")
                .scanStatus(ScanStatus.PAUSED)
                .build();

        // Act
        ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, checkpoint);

        // Assert
        assertThat(result.remaining()).isEmpty();
        assertThat(result.analyzedOffset()).isEqualTo(2);
    }

    private ConfluencePage createPage(String id) {
        return ConfluencePage.builder()
                .id(id)
                .title("Page " + id)
                .spaceKey("SPACE1")
                .build();
    }
}
