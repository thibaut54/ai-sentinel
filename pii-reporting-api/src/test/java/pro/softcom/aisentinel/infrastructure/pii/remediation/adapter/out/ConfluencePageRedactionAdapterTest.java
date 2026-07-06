package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceClient;
import pro.softcom.aisentinel.application.pii.remediation.port.out.SourcePageRedactionPort.PageRedactionResult;
import pro.softcom.aisentinel.application.pii.remediation.port.out.SourcePageRedactionPort.PageRedactionStatus;
import pro.softcom.aisentinel.application.pii.remediation.port.out.SourcePageRedactionPort.ValueRedactionStatus;
import pro.softcom.aisentinel.application.pii.remediation.port.out.SourcePageRedactionPort.ValueReplacement;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage.HtmlContent;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage.PageMetadata;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.ConfluenceApiException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfluencePageRedactionAdapter")
class ConfluencePageRedactionAdapterTest {

    private static final String PAGE_ID = "12345";
    private static final String EMAIL = "john.doe@example.com";
    private static final String BODY_WITH_EMAIL = "<p>Contact: " + EMAIL + " for details</p>";
    private static final List<ValueReplacement> EMAIL_REPLACEMENT =
            List.of(new ValueReplacement(EMAIL, "[EMAIL]"));

    @Mock
    private ConfluenceClient confluenceClient;

    private ConfluencePageRedactionAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ConfluencePageRedactionAdapter(confluenceClient, new StorageContentRedactor());
    }

    @Nested
    @DisplayName("redactPage")
    class RedactPage {

        @Test
        @DisplayName("Should_UpdatePageWithIncrementedVersion_When_ValueFoundInStorageBody")
        void Should_UpdatePageWithIncrementedVersion_When_ValueFoundInStorageBody() {
            stubPage(page(BODY_WITH_EMAIL, 5));
            when(confluenceClient.updatePage(any()))
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(invocation.getArgument(0)));

            PageRedactionResult result = adapter.redactPage(PAGE_ID, EMAIL_REPLACEMENT);

            ArgumentCaptor<ConfluencePage> captor = ArgumentCaptor.forClass(ConfluencePage.class);
            verify(confluenceClient).updatePage(captor.capture());
            ConfluencePage updated = captor.getValue();
            assertSoftly(softly -> {
                softly.assertThat(result.pageStatus()).isEqualTo(PageRedactionStatus.UPDATED);
                softly.assertThat(result.valueStatuses()).containsExactly(ValueRedactionStatus.REDACTED);
                softly.assertThat(updated.metadata().version()).isEqualTo(6);
                softly.assertThat(updated.content().body()).contains("[EMAIL]").doesNotContain(EMAIL);
                softly.assertThat(updated.content().format()).isEqualTo("storage");
            });
        }

        @Test
        @DisplayName("Should_ReportValueNotFoundWithoutUpdate_When_ValueAbsentFromBody")
        void Should_ReportValueNotFoundWithoutUpdate_When_ValueAbsentFromBody() {
            stubPage(page("<p>No sensitive content here</p>", 5));

            PageRedactionResult result = adapter.redactPage(PAGE_ID, EMAIL_REPLACEMENT);

            verify(confluenceClient, never()).updatePage(any());
            assertSoftly(softly -> {
                softly.assertThat(result.pageStatus()).isEqualTo(PageRedactionStatus.NO_MATCHES);
                softly.assertThat(result.valueStatuses())
                        .containsExactly(ValueRedactionStatus.VALUE_NOT_FOUND);
            });
        }

        @Test
        @DisplayName("Should_RetryOnceThenSucceed_When_FirstUpdateHitsVersionConflict")
        void Should_RetryOnceThenSucceed_When_FirstUpdateHitsVersionConflict() {
            when(confluenceClient.getPage(PAGE_ID))
                    .thenReturn(CompletableFuture.completedFuture(Optional.of(page(BODY_WITH_EMAIL, 5))))
                    .thenReturn(CompletableFuture.completedFuture(Optional.of(page(BODY_WITH_EMAIL, 6))));
            when(confluenceClient.updatePage(any()))
                    .thenReturn(CompletableFuture.failedFuture(conflict()))
                    .thenAnswer(invocation -> CompletableFuture.completedFuture(invocation.getArgument(0)));

            PageRedactionResult result = adapter.redactPage(PAGE_ID, EMAIL_REPLACEMENT);

            ArgumentCaptor<ConfluencePage> captor = ArgumentCaptor.forClass(ConfluencePage.class);
            verify(confluenceClient, times(2)).updatePage(captor.capture());
            assertSoftly(softly -> {
                softly.assertThat(result.pageStatus()).isEqualTo(PageRedactionStatus.UPDATED);
                softly.assertThat(captor.getAllValues().getLast().metadata().version()).isEqualTo(7);
            });
        }

        @Test
        @DisplayName("Should_ReportStale_When_VersionConflictPersistsAfterRetry")
        void Should_ReportStale_When_VersionConflictPersistsAfterRetry() {
            stubPage(page(BODY_WITH_EMAIL, 5));
            when(confluenceClient.updatePage(any()))
                    .thenReturn(CompletableFuture.failedFuture(conflict()))
                    .thenReturn(CompletableFuture.failedFuture(conflict()));

            PageRedactionResult result = adapter.redactPage(PAGE_ID, EMAIL_REPLACEMENT);

            verify(confluenceClient, times(2)).updatePage(any());
            assertSoftly(softly -> {
                softly.assertThat(result.pageStatus()).isEqualTo(PageRedactionStatus.STALE);
                softly.assertThat(result.valueStatuses()).isEmpty();
            });
        }

        @Test
        @DisplayName("Should_ReportFailed_When_UpdateFailsWithNonConflictHttpError")
        void Should_ReportFailed_When_UpdateFailsWithNonConflictHttpError() {
            stubPage(page(BODY_WITH_EMAIL, 5));
            when(confluenceClient.updatePage(any()))
                    .thenReturn(CompletableFuture.failedFuture(
                            new ConfluenceApiException("Error updating page", 500, "boom")));

            PageRedactionResult result = adapter.redactPage(PAGE_ID, EMAIL_REPLACEMENT);

            assertThat(result.pageStatus()).isEqualTo(PageRedactionStatus.FAILED);
        }

        @Test
        @DisplayName("Should_ReportFailed_When_PageIsNotReadable")
        void Should_ReportFailed_When_PageIsNotReadable() {
            when(confluenceClient.getPage(PAGE_ID))
                    .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

            PageRedactionResult result = adapter.redactPage(PAGE_ID, EMAIL_REPLACEMENT);

            verify(confluenceClient, never()).updatePage(any());
            assertThat(result.pageStatus()).isEqualTo(PageRedactionStatus.FAILED);
        }
    }

    private void stubPage(ConfluencePage page) {
        when(confluenceClient.getPage(PAGE_ID))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(page)));
    }

    private static ConfluencePage page(String storageBody, int version) {
        return ConfluencePage.builder()
                .id(PAGE_ID)
                .title("Team Page")
                .spaceKey("SPACE")
                .content(new HtmlContent(storageBody))
                .metadata(new PageMetadata("author", LocalDateTime.of(2026, 1, 1, 8, 0),
                        "editor", LocalDateTime.of(2026, 7, 1, 9, 0), version, "current"))
                .labels(List.of())
                .build();
    }

    private static ConfluenceApiException conflict() {
        return new ConfluenceApiException("Error updating page", 409, "version conflict");
    }
}
