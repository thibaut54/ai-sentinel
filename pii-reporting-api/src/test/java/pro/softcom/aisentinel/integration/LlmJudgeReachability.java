package pro.softcom.aisentinel.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for verifying LM Studio reachability with a valid Qwen 3.6 A3B model.
 *
 * <p>Used by integration tests to skip gracefully when LM Studio is unavailable (spec §4.1).
 * The check queries {@code GET {baseUrl}/models} and verifies that at least one model
 * matches {@code qwen3.6 ∧ a3b} while being excluded from the finetune blacklist.
 *
 * <p>Finetune blacklist (spec §2.3 + §8.4): {@code uncensored}, {@code heretic},
 * {@code distilled}, {@code aggressive}, {@code finetune}.
 */
public final class LlmJudgeReachability {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeReachability.class);

    static final List<String> FINETUNE_BLACKLIST = List.of(
        "uncensored", "heretic", "distilled", "aggressive", "finetune"
    );

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJudgeReachability() {
        // Utility class — no instantiation
    }

    /**
     * Returns the base URL from system property {@code llm.judge.base-url},
     * defaulting to {@code http://172.22.22.63:1234/v1}.
     */
    public static String resolveBaseUrl() {
        return System.getProperty("llm.judge.base-url", "http://172.22.22.63:1234/v1");
    }

    /**
     * Checks whether a valid Qwen 3.6 A3B model is accessible at the configured LM Studio endpoint.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>GET {@code {baseUrl}/models} with a 5-second timeout</li>
     *   <li>Parse JSON, extract the {@code data[].id} list</li>
     *   <li>Look for at least one id containing {@code qwen3.6} AND {@code a3b},
     *       excluding ids that match any finetune blacklist marker</li>
     * </ol>
     *
     * @return {@code true} if a valid model is found; {@code false} on any error
     *         (network, timeout, JSON parse, no matching model)
     */
    public static boolean isReachable() {
        String baseUrl = resolveBaseUrl();
        return isReachable(baseUrl);
    }

    /**
     * Same as {@link #isReachable()} but against the given base URL (useful for unit tests).
     */
    static boolean isReachable(String baseUrl) {
        try {
            List<String> modelIds = fetchModelIds(baseUrl);
            List<String> candidates = filterCandidates(modelIds);

            if (candidates.isEmpty()) {
                log.warn("[LLM-JUDGE] No valid Qwen 3.6 A3B model found at {}. Available: {}",
                    baseUrl, modelIds);
                return false;
            }

            log.info("[LLM-JUDGE] Reachable at {}. Matching models: {}", baseUrl, candidates);
            return true;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[LLM-JUDGE] Unreachable at {}: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    /**
     * Fetches the list of model IDs from {@code GET {baseUrl}/models}.
     *
     * @return list of model id strings from the response
     * @throws IOException          on HTTP or parse failure
     * @throws InterruptedException if the HTTP call is interrupted
     */
    static List<String> fetchModelIds(String baseUrl) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(HTTP_TIMEOUT)
            .build();

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/models"))
            .timeout(HTTP_TIMEOUT)
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("LM Studio /models returned HTTP " + response.statusCode());
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode data = root.path("data");

        List<String> ids = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode model : data) {
                String id = model.path("id").asText(null);
                if (id != null && !id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * Filters model IDs to retain only valid Qwen 3.6 A3B candidates (not fine-tuned).
     *
     * @param modelIds the full list of model ids from LM Studio
     * @return list of ids matching {@code qwen3.6 ∧ a3b} without blacklisted markers
     */
    static List<String> filterCandidates(List<String> modelIds) {
        return modelIds.stream()
            .filter(id -> {
                String lower = id.toLowerCase();
                return lower.contains("qwen3.6") && lower.contains("a3b");
            })
            .filter(id -> {
                String lower = id.toLowerCase();
                return FINETUNE_BLACKLIST.stream().noneMatch(lower::contains);
            })
            .toList();
    }
}
