package pro.softcom.aisentinel.domain.pii.reporting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage;
import pro.softcom.aisentinel.domain.pii.ScanStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@DisplayName("ScanRemainingPagesCalculator")
class ScanRemainingPagesCalculatorTest {

    private static ConfluencePage page(String id) {
        return ConfluencePage.builder().id(id).title("Title " + id).spaceKey("SPACE").build();
    }

    private static ScanCheckpoint checkpoint(String lastPageId, String lastAttachment, ScanStatus status) {
        return ScanCheckpoint.builder()
                .scanId("scan-1")
                .spaceKey("SPACE")
                .lastProcessedPageId(lastPageId)
                .lastProcessedAttachmentName(lastAttachment)
                .scanStatus(status)
                .build();
    }

    @Nested
    @DisplayName("computeRemainPages - nominal cases")
    class ComputeRemainPagesNominal {

        @Test
        @DisplayName("Should_ReturnAllPages_When_CheckpointIsNull")
        void Should_ReturnAllPages_When_CheckpointIsNull() {
            // Arrange
            List<ConfluencePage> pages = List.of(page("p1"), page("p2"), page("p3"));

            // Act
            ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, null);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.originalTotal()).isEqualTo(3);
                softly.assertThat(result.analyzedOffset()).isEqualTo(0);
                softly.assertThat(result.remaining()).containsExactlyElementsOf(pages);
            });
        }

        @Test
        @DisplayName("Should_ReturnEmptyList_When_CheckpointIsCompleted")
        void Should_ReturnEmptyList_When_CheckpointIsCompleted() {
            // Arrange
            List<ConfluencePage> pages = List.of(page("p1"), page("p2"));
            ScanCheckpoint cp = checkpoint("p1", null, ScanStatus.COMPLETED);

            // Act
            ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, cp);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.originalTotal()).isEqualTo(2);
                softly.assertThat(result.remaining()).isEmpty();
            });
        }

        @Test
        @DisplayName("Should_ReturnPagesAfterLastProcessed_When_RunningCheckpointWithKnownPage")
        void Should_ReturnPagesAfterLastProcessed_When_RunningCheckpointWithKnownPage() {
            // Arrange
            List<ConfluencePage> pages = List.of(page("p1"), page("p2"), page("p3"), page("p4"));
            ScanCheckpoint cp = checkpoint("p2", null, ScanStatus.RUNNING);

            // Act
            ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, cp);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.originalTotal()).isEqualTo(4);
                softly.assertThat(result.analyzedOffset()).isEqualTo(2); // pages p1, p2 already done
                softly.assertThat(result.remaining()).extracting(ConfluencePage::id)
                        .containsExactly("p3", "p4");
            });
        }

        @Test
        @DisplayName("Should_IncludeLastPage_When_AttachmentWasInProgress")
        void Should_IncludeLastPage_When_AttachmentWasInProgress() {
            // Arrange
            List<ConfluencePage> pages = List.of(page("p1"), page("p2"), page("p3"));
            ScanCheckpoint cp = checkpoint("p2", "attachment.pdf", ScanStatus.RUNNING);

            // Act
            ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, cp);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.remaining()).extracting(ConfluencePage::id)
                        .containsExactly("p2", "p3");
                // analyzedOffset decremented by 1 because attachment was in progress
                softly.assertThat(result.analyzedOffset()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("Should_ReturnEmptyList_When_PagesListIsNull")
        void Should_ReturnEmptyList_When_PagesListIsNull() {
            // Act
            ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(null, null);

            // Assert
            assertSoftly(softly -> {
                softly.assertThat(result.originalTotal()).isEqualTo(0);
                softly.assertThat(result.remaining()).isEmpty();
                softly.assertThat(result.analyzedOffset()).isEqualTo(0);
            });
        }
    }

    @Nested
    @DisplayName("computeRemainingPages - edge cases")
    class ComputeRemainingPagesEdgeCases {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"p-unknown"})
        @DisplayName("Should_ReturnAllPages_When_LastProcessedPageIdIsBlankNullOrUnknown")
        void Should_ReturnAllPages_When_LastProcessedPageIdIsBlankNullOrUnknown(String lastProcessedPageId) {
            // Arrange
            List<ConfluencePage> pages = List.of(page("p1"), page("p2"));
            ScanCheckpoint cp = checkpoint(lastProcessedPageId, null, ScanStatus.RUNNING);

            // Act
            List<ConfluencePage> remaining = ScanRemainingPagesCalculator.computeRemainingPages(pages, cp);

            // Assert
            assertThat(remaining).containsExactlyElementsOf(pages);
        }

        @Test
        @DisplayName("Should_ReturnEmptyList_When_LastProcessedPageIsLastInList")
        void Should_ReturnEmptyList_When_LastProcessedPageIsLastInList() {
            // Arrange
            List<ConfluencePage> pages = List.of(page("p1"), page("p2"), page("p3"));
            ScanCheckpoint cp = checkpoint("p3", null, ScanStatus.RUNNING);

            // Act
            List<ConfluencePage> remaining = ScanRemainingPagesCalculator.computeRemainingPages(pages, cp);

            // Assert
            assertThat(remaining).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnEmptyList_When_PagesListIsEmpty")
        void Should_ReturnEmptyList_When_PagesListIsEmpty() {
            // Arrange
            ScanCheckpoint cp = checkpoint("p1", null, ScanStatus.RUNNING);

            // Act
            List<ConfluencePage> remaining = ScanRemainingPagesCalculator.computeRemainingPages(List.of(), cp);

            // Assert
            assertThat(remaining).isEmpty();
        }

        @Test
        @DisplayName("Should_ReturnAllPages_When_CheckpointIsNull")
        void Should_ReturnAllPages_When_CheckpointIsNullForRemainingPages() {
            // Arrange
            List<ConfluencePage> pages = List.of(page("p1"), page("p2"));

            // Act
            List<ConfluencePage> remaining = ScanRemainingPagesCalculator.computeRemainingPages(pages, null);

            // Assert
            assertThat(remaining).containsExactlyElementsOf(pages);
        }

        @Test
        @DisplayName("Should_ReturnLastPageAndRest_When_AttachmentWasInProgressOnLastPage")
        void Should_ReturnLastPageAndRest_When_AttachmentWasInProgressOnLastPage() {
            // Arrange
            List<ConfluencePage> pages = List.of(page("p1"), page("p2"), page("p3"));
            ScanCheckpoint cp = checkpoint("p3", "doc.pdf", ScanStatus.RUNNING);

            // Act
            List<ConfluencePage> remaining = ScanRemainingPagesCalculator.computeRemainingPages(pages, cp);

            // Assert
            assertThat(remaining).extracting(ConfluencePage::id).containsExactly("p3");
        }
    }

    @Nested
    @DisplayName("computeAnalyzedOffset - edge cases")
    class ComputeAnalyzedOffsetEdgeCases {

        @Test
        @DisplayName("Should_ReturnZeroOffset_When_CheckpointIsNull")
        void Should_ReturnZeroOffset_When_CheckpointIsNull() {
            // Arrange
            List<ConfluencePage> pages = List.of(page("p1"), page("p2"));

            // Act
            ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, null);

            // Assert
            assertThat(result.analyzedOffset()).isZero();
        }

        @Test
        @DisplayName("Should_ReturnZeroOffset_When_PageIdNotFound")
        void Should_ReturnZeroOffset_When_PageIdNotFound() {
            // Arrange
            List<ConfluencePage> pages = List.of(page("p1"), page("p2"));
            ScanCheckpoint cp = checkpoint("unknown", null, ScanStatus.RUNNING);

            // Act
            ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, cp);

            // Assert
            assertThat(result.analyzedOffset()).isZero();
        }

        @Test
        @DisplayName("Should_NotGoBelowZeroOffset_When_AttachmentInProgressAndOffsetWouldBeNegative")
        void Should_NotGoBelowZeroOffset_When_AttachmentInProgressAndOffsetWouldBeNegative() {
            // Arrange: only one page, it was the last processed, with an attachment in progress
            List<ConfluencePage> pages = List.of(page("p1"));
            // p1 is at index 0, analyzedOffset = 1; hadInProgressAttachment -> Math.max(0, 0) = 0
            ScanCheckpoint cp = checkpoint("p1", "file.pdf", ScanStatus.RUNNING);

            // Act
            ScanRemainingPages result = ScanRemainingPagesCalculator.computeRemainPages(pages, cp);

            // Assert: offset must not go negative
            assertThat(result.analyzedOffset()).isGreaterThanOrEqualTo(0);
        }
    }
}
