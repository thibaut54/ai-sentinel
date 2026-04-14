package pro.softcom.aisentinel.infrastructure.estimation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.confluence.service.ConfluenceAccessor;
import pro.softcom.aisentinel.application.pii.reporting.service.parser.HtmlContentParser;
import pro.softcom.aisentinel.domain.confluence.ConfluencePage;
import pro.softcom.aisentinel.domain.confluence.ConfluenceSpace;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.config.ConfluenceConnectionConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "estimation.content-count.enabled", havingValue = "true")
public class ConfluenceContentCountRunner implements ApplicationRunner {

    private static final int ESTIMATED_CHARS_PER_TOKEN = 4;
    private static final int PROGRESS_LOG_INTERVAL = 50;
    private static final String TAG = "[CONTENT-COUNT]";

    private final ConfluenceAccessor confluenceAccessor;
    private final HtmlContentParser htmlContentParser;
    private final ConfluenceConnectionConfig confluenceConfig;

    @Override
    public void run(ApplicationArguments args) {
        Thread.startVirtualThread(this::countAllContent);
    }

    private void countAllContent() {
        log.info("{} Starting — baseUrl={}, pagesLimit={}, maxPages={}, deploymentType={}",
            TAG, confluenceConfig.baseUrl(), confluenceConfig.pagesLimit(),
            confluenceConfig.maxPages(), confluenceConfig.deploymentType());

        if (confluenceConfig.maxPages() <= 0) {
            log.error("{} ABORTED: maxPages={}", TAG, confluenceConfig.maxPages());
            return;
        }

        List<ConfluenceSpace> spaces = fetchAllSpaces();
        if (spaces == null || spaces.isEmpty()) {
            return;
        }

        // Diagnostic: raw HTTP call on first space to debug 0-page issue
        runDiagnostic(spaces.getFirst());

        long globalStartNs = System.nanoTime();
        long totalChars = 0;
        long totalPages = 0;
        long totalFetchMs = 0;
        long totalCleanMs = 0;
        int emptySpaces = 0;
        int failedSpaces = 0;

        for (int i = 0; i < spaces.size(); i++) {
            SpaceCountResult result = countSpaceContent(spaces.get(i));
            totalChars += result.chars;
            totalPages += result.pages;
            totalFetchMs += result.fetchMs;
            totalCleanMs += result.cleanMs;
            if (result.failed) failedSpaces++;
            else if (result.pages == 0) emptySpaces++;

            if ((i + 1) % PROGRESS_LOG_INTERVAL == 0) {
                log.info("{} Progress: {}/{} spaces | {} pages | {} chars",
                    TAG, i + 1, spaces.size(), fmt(totalPages), fmt(totalChars));
            }
        }

        logFinalResults(spaces.size(), totalPages, totalChars, totalFetchMs, totalCleanMs,
            emptySpaces, failedSpaces, globalStartNs);
    }

    private void runDiagnostic(ConfluenceSpace firstSpace) {
        String base = confluenceConfig.baseUrl();
        String url = (base.endsWith("/") ? base : base + "/")
            + "rest/api/space/" + firstSpace.key() + "/content?expand=version,body.storage&limit=5&start=0";

        log.info("{} DIAGNOSTIC — GET {}", TAG, url);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + confluenceConfig.apiToken().trim())
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            String snippet = body.length() > 500 ? body.substring(0, 500) + "..." : body;
            log.info("{} DIAGNOSTIC — HTTP {} | body[{}]: {}", TAG, response.statusCode(), body.length(), snippet);
        } catch (Exception e) {
            log.error("{} DIAGNOSTIC — failed: {}", TAG, e.getMessage());
        }
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

    private SpaceCountResult countSpaceContent(ConfluenceSpace space) {
        long fetchStart = System.nanoTime();

        List<ConfluencePage> pages;
        try {
            pages = confluenceAccessor.getAllPagesInSpace(space.key()).get();
        } catch (Exception e) {
            return SpaceCountResult.FAILED;
        }

        long fetchMs = msElapsed(fetchStart);
        long cleanStart = System.nanoTime();

        long spaceChars = 0;
        for (ConfluencePage page : pages) {
            String rawBody = page.content() != null ? page.content().body() : "";
            String cleaned = htmlContentParser.cleanText(rawBody);
            spaceChars += cleaned != null ? cleaned.length() : 0;
        }

        long cleanMs = msElapsed(cleanStart);
        return new SpaceCountResult(spaceChars, pages.size(), fetchMs, cleanMs, false);
    }

    private void logFinalResults(int spacesCount, long totalPages, long totalChars,
                                  long totalFetchMs, long totalCleanMs,
                                  int emptySpaces, int failedSpaces, long globalStartNs) {
        long totalElapsedMs = msElapsed(globalStartNs);
        long estimatedTokens = totalChars / ESTIMATED_CHARS_PER_TOKEN;
        long avgCharsPerPage = totalPages > 0 ? totalChars / totalPages : 0;

        String report = String.format(Locale.ROOT, """

            %s ════════════════════════════════════════════════════════
            %s CONFLUENCE CONTENT COUNT — FINAL RESULTS
            %s ────────────────────────────────────────────────────────
            %s Spaces scanned      : %d (empty: %d, failed: %d)
            %s Total pages         : %s
            %s Total characters    : %s (%.1f MB)
            %s Estimated tokens    : %s (~chars/%d)
            %s Avg chars/page      : %s
            %s ────────────────────────────────────────────────────────
            %s Time — API fetch    : %s ms (%d min)
            %s Time — HTML clean   : %s ms (%d min)
            %s Time — Total        : %s ms (%d min)
            %s Throughput          : %s pages/sec | %s chars/sec
            %s ════════════════════════════════════════════════════════""",
            TAG, TAG, TAG,
            TAG, spacesCount, emptySpaces, failedSpaces,
            TAG, fmt(totalPages),
            TAG, fmt(totalChars), totalChars / 1_000_000.0,
            TAG, fmt(estimatedTokens), ESTIMATED_CHARS_PER_TOKEN,
            TAG, fmt(avgCharsPerPage),
            TAG,
            TAG, fmt(totalFetchMs), totalFetchMs / 60_000,
            TAG, fmt(totalCleanMs), totalCleanMs / 60_000,
            TAG, fmt(totalElapsedMs), totalElapsedMs / 60_000,
            TAG, totalElapsedMs > 0 ? fmt(totalPages * 1000 / totalElapsedMs) : "N/A",
                 totalElapsedMs > 0 ? fmt(totalChars * 1000 / totalElapsedMs) : "N/A",
            TAG);

        log.info(report);
    }

    private static long msElapsed(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private static String fmt(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private record SpaceCountResult(long chars, int pages, long fetchMs, long cleanMs, boolean failed) {
        static final SpaceCountResult FAILED = new SpaceCountResult(0, 0, 0, 0, true);
    }
}
