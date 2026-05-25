package pro.softcom.aisentinel.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Smoke test for LM Studio reachability with a valid Qwen 3.6 A3B model.
 *
 * <p>This test does NOT use Spring context or Testcontainers — it simply queries
 * the live LM Studio endpoint. Both tests are guarded by {@link LlmJudgeReachability#isReachable()}
 * and will be skipped (not failed) in CI or whenever LM Studio is unavailable.
 *
 * <p>Target endpoint: {@code http://172.22.22.63:1234/v1/models} (spec §1.5).
 * Override via system property: {@code -Dllm.judge.base-url=http://host:port/v1}.
 */
class LlmJudgeReachabilityIT {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeReachabilityIT.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void Should_ConfirmLmStudioIsReachable_When_TargetIsLive() {
        // Arrange
        String baseUrl = LlmJudgeReachability.resolveBaseUrl();

        // Assume — skip gracefully if LM Studio is not accessible
        assumeTrue(
            LlmJudgeReachability.isReachable(),
            "LLM judge unreachable at " + baseUrl + " — skipping smoke test. "
                + "Start LM Studio with qwen/qwen3.6-35b-a3b loaded and retry."
        );

        // Act — re-check reachability (idempotent; proves the check is deterministic)
        boolean reachable = LlmJudgeReachability.isReachable();

        // Assert
        assertThat(reachable)
            .as("LM Studio should remain reachable during the same test run")
            .isTrue();

        log.info("[LLM-JUDGE][SMOKE] LM Studio confirmed reachable at {}", baseUrl);
    }

    @Test
    void Should_LogAvailableModels_When_Reachable() {
        // Arrange
        String baseUrl = LlmJudgeReachability.resolveBaseUrl();

        // Assume — skip if LM Studio is unavailable
        assumeTrue(
            LlmJudgeReachability.isReachable(),
            "LLM judge unreachable at " + baseUrl + " — skipping model discovery log."
        );

        // Act
        List<String> modelIds;
        try {
            modelIds = LlmJudgeReachability.fetchModelIds(baseUrl);
        } catch (Exception e) {
            // If we passed the assumeTrue above but fail here, something changed mid-test
            throw new AssertionError("fetchModelIds failed despite isReachable() returning true", e);
        }

        List<String> candidates = LlmJudgeReachability.filterCandidates(modelIds);

        // Assert
        assertThat(modelIds)
            .as("LM Studio should expose at least one model")
            .isNotEmpty();

        assertThat(candidates)
            .as("At least one valid Qwen 3.6 A3B model (not fine-tuned) must be present")
            .isNotEmpty();

        // Log — useful for triage in CI logs / test reports
        log.info("[LLM-JUDGE][SMOKE] All models at {}: {}", baseUrl, modelIds);
        log.info("[LLM-JUDGE][SMOKE] Valid Qwen 3.6 A3B candidates: {}", candidates);
        log.info("[LLM-JUDGE][SMOKE] Finetune blacklist applied: {}", LlmJudgeReachability.FINETUNE_BLACKLIST);
    }
}
