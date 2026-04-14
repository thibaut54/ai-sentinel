package pro.softcom.aisentinel.infrastructure.estimation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.confluence.port.out.AttachmentTextExtractor;
import pro.softcom.aisentinel.application.confluence.port.out.ConfluenceAttachmentDownloader;
import pro.softcom.aisentinel.application.confluence.service.ConfluenceAccessor;
import pro.softcom.aisentinel.domain.confluence.AttachmentInfo;
import pro.softcom.aisentinel.domain.confluence.AttachmentTypeFilter;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage;
import pro.softcom.aisentinel.domain.confluence.ConfluenceSpace;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "estimation.attachment-count.enabled", havingValue = "true")
public class AttachmentContentCountRunner implements ApplicationRunner {

    private static final int ESTIMATED_CHARS_PER_TOKEN = 4;
    private static final int PROGRESS_LOG_INTERVAL = 500;
    private static final String TAG = "[ATTACHMENT-COUNT]";

    private final ConfluenceAccessor confluenceAccessor;
    private final ConfluenceAttachmentDownloader attachmentDownloader;
    private final AttachmentTextExtractor textExtractor;

    @Override
    public void run(ApplicationArguments args) {
        Thread.startVirtualThread(this::countAllAttachments);
    }

    private void countAllAttachments() {
        log.info("{} Starting attachment content counting...", TAG);

        List<ConfluenceSpace> spaces = fetchAllSpaces();
        if (spaces == null) return;

        long globalStartNs = System.nanoTime();
        long totalChars = 0;
        long totalAttachments = 0;
        long totalExtractable = 0;
        long totalSkipped = 0;
        long totalDownloadFailed = 0;
        long totalExtractFailed = 0;
        long totalPagesProcessed = 0;
        long totalDownloadMs = 0;
        long totalExtractMs = 0;

        for (ConfluenceSpace space : spaces) {
            List<ConfluencePage> pages = fetchPages(space.key());
            if (pages == null) continue;

            for (ConfluencePage page : pages) {
                List<AttachmentInfo> attachments = fetchAttachments(page.id());
                if (attachments == null || attachments.isEmpty()) continue;

                for (AttachmentInfo attachment : attachments) {
                    totalAttachments++;

                    if (!AttachmentTypeFilter.isExtractable(attachment)) {
                        totalSkipped++;
                        continue;
                    }
                    totalExtractable++;

                    long dlStart = System.nanoTime();
                    byte[] bytes = downloadAttachment(page.id(), attachment.name());
                    totalDownloadMs += msElapsed(dlStart);

                    if (bytes == null) {
                        totalDownloadFailed++;
                        continue;
                    }

                    long extStart = System.nanoTime();
                    Optional<String> text = extractText(attachment, bytes);
                    totalExtractMs += msElapsed(extStart);

                    if (text.isPresent()) {
                        totalChars += text.get().length();
                    } else {
                        totalExtractFailed++;
                    }

                    if ((totalExtractable) % PROGRESS_LOG_INTERVAL == 0) {
                        log.info("{} Progress: {} extractable processed | {} chars so far",
                            TAG, totalExtractable, fmt(totalChars));
                    }
                }

                totalPagesProcessed++;
            }
        }

        long totalElapsedMs = msElapsed(globalStartNs);
        long estimatedTokens = totalChars / ESTIMATED_CHARS_PER_TOKEN;
        long successfulExtractions = totalExtractable - totalDownloadFailed - totalExtractFailed;

        String report = String.format(Locale.ROOT, """

            %s ════════════════════════════════════════════════════════
            %s ATTACHMENT CONTENT COUNT — FINAL RESULTS
            %s ────────────────────────────────────────────────────────
            %s Pages with attachments scanned : %s
            %s Total attachments found        : %s
            %s   Extractable (supported type) : %s
            %s   Skipped (unsupported type)   : %s
            %s   Download failed              : %s
            %s   Extract failed (Tika)        : %s
            %s   Successfully extracted       : %s
            %s ────────────────────────────────────────────────────────
            %s Total characters               : %s (%.1f MB)
            %s Estimated tokens               : %s (~chars/%d)
            %s Avg chars/attachment            : %s
            %s ────────────────────────────────────────────────────────
            %s Time — Download                : %s ms (%d min)
            %s Time — Tika extract            : %s ms (%d min)
            %s Time — Total                   : %s ms (%d min)
            %s ════════════════════════════════════════════════════════""",
            TAG, TAG, TAG,
            TAG, fmt(totalPagesProcessed),
            TAG, fmt(totalAttachments),
            TAG, fmt(totalExtractable),
            TAG, fmt(totalSkipped),
            TAG, fmt(totalDownloadFailed),
            TAG, fmt(totalExtractFailed),
            TAG, fmt(successfulExtractions),
            TAG,
            TAG, fmt(totalChars), totalChars / 1_000_000.0,
            TAG, fmt(estimatedTokens), ESTIMATED_CHARS_PER_TOKEN,
            TAG, successfulExtractions > 0 ? fmt(totalChars / successfulExtractions) : "N/A",
            TAG,
            TAG, fmt(totalDownloadMs), totalDownloadMs / 60_000,
            TAG, fmt(totalExtractMs), totalExtractMs / 60_000,
            TAG, fmt(totalElapsedMs), totalElapsedMs / 60_000,
            TAG);

        log.info(report);
    }

    private List<ConfluenceSpace> fetchAllSpaces() {
        try {
            List<ConfluenceSpace> spaces = confluenceAccessor.getAllSpaces().get();
            log.info("{} Found {} spaces", TAG, spaces.size());
            return spaces;
        } catch (Exception e) {
            log.error("{} Failed to fetch spaces: {}", TAG, e.getMessage());
            return null;
        }
    }

    private List<ConfluencePage> fetchPages(String spaceKey) {
        try {
            return confluenceAccessor.getAllPagesInSpace(spaceKey).get();
        } catch (Exception e) {
            return null;
        }
    }

    private List<AttachmentInfo> fetchAttachments(String pageId) {
        try {
            return confluenceAccessor.getPageAttachments(pageId).get();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] downloadAttachment(String pageId, String attachmentName) {
        try {
            Optional<byte[]> result = attachmentDownloader.downloadAttachmentContent(pageId, attachmentName).get();
            return result.orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Optional<String> extractText(AttachmentInfo attachment, byte[] bytes) {
        try {
            return textExtractor.extractText(attachment, bytes);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static long msElapsed(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private static String fmt(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
