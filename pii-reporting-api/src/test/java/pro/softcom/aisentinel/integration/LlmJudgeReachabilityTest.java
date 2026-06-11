package pro.softcom.aisentinel.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LlmJudgeReachability}.
 * Tests the filtering logic without network access, using predefined model ID lists.
 */
class LlmJudgeReachabilityTest {

    private String originalBaseUrl;

    @BeforeEach
    void captureSystemProperty() {
        originalBaseUrl = System.getProperty("llm.judge.base-url");
    }

    @AfterEach
    void restoreSystemProperty() {
        if (originalBaseUrl == null) {
            System.clearProperty("llm.judge.base-url");
        } else {
            System.setProperty("llm.judge.base-url", originalBaseUrl);
        }
    }

    // ========================== filterCandidates tests ==========================

    @Test
    void Should_ReturnCandidate_When_ModelMatchesQwen36A3b() {
        // Arrange
        List<String> modelIds = List.of(
            "qwen/qwen3.6-35b-a3b",
            "text-embedding-nomic-embed-text-v1.5",
            "dolphin-2.0-mistral-7b"
        );

        // Act
        List<String> candidates = LlmJudgeReachability.filterCandidates(modelIds);

        // Assert
        assertThat(candidates)
            .as("Should find the exact Qwen 3.6 A3B model")
            .containsExactly("qwen/qwen3.6-35b-a3b");
    }

    @Test
    void Should_ReturnFalse_When_OnlyFinetunedModelsPresent() {
        // Arrange — all 5 blacklist markers from spec §2.3 + §8.4
        List<String> fineTunedOnly = List.of(
            "qwen3.6-35b-a3b-uncensored-hauhaucs-aggressive",      // uncensored + aggressive
            "qwen3.6-27b-heretic-uncensored-finetune-neo-code",     // heretic + uncensored + finetune
            "qwen3.6-35b-a3b-claude-4.6-opus-reasoning-distilled", // distilled
            "qwen3.6-27b-uncensored-heretic-v2-native"             // uncensored + heretic
        );

        // Act
        List<String> candidates = LlmJudgeReachability.filterCandidates(fineTunedOnly);

        // Assert
        assertThat(candidates)
            .as("All fine-tuned models should be excluded by the blacklist")
            .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "qwen3.6-35b-a3b-uncensored",
        "qwen3.6-35b-a3b-heretic",
        "qwen3.6-35b-a3b-reasoning-distilled",
        "qwen3.6-35b-a3b-aggressive-v2",
        "qwen3.6-35b-a3b-custom-finetune"
    })
    void Should_ExcludeModel_When_BlacklistMarkerPresent(String blacklistedModelId) {
        // Arrange
        List<String> models = List.of(blacklistedModelId);

        // Act
        List<String> candidates = LlmJudgeReachability.filterCandidates(models);

        // Assert
        assertThat(candidates)
            .as("Model '%s' contains a blacklist marker and should be excluded", blacklistedModelId)
            .isEmpty();
    }

    @Test
    void Should_ReturnMultipleCandidates_When_SeveralValidModelsPresent() {
        // Arrange — matches the real inventory on 172.22.22.63 (spec §8.4)
        List<String> allModels = List.of(
            "qwen/qwen3.6-27b",
            "qwen/qwen3.6-35b-a3b",         // valid
            "qwen3.6-35b-a3b-uncensored",   // blacklisted
            "qwen3.6-27b-heretic-v2",       // blacklisted (no a3b anyway)
            "google/gemma-4-31b",
            "dolphin-2.0-mistral-7b"
        );

        // Act
        List<String> candidates = LlmJudgeReachability.filterCandidates(allModels);

        // Assert
        assertThat(candidates)
            .as("Only the non-fine-tuned a3b model should pass")
            .containsExactly("qwen/qwen3.6-35b-a3b");
    }

    @Test
    void Should_ReturnEmpty_When_NoQwenModelsAtAll() {
        // Arrange
        List<String> unrelatedModels = List.of(
            "google/gemma-4-31b",
            "dolphin-2.5-mixtral-8x7b@q6_k",
            "openai/gpt-oss-20b"
        );

        // Act
        List<String> candidates = LlmJudgeReachability.filterCandidates(unrelatedModels);

        // Assert
        assertThat(candidates)
            .as("No Qwen 3.6 A3B present — candidates should be empty")
            .isEmpty();
    }

    // ========================== resolveBaseUrl tests ==========================

    @Test
    void Should_ReturnDefaultUrl_When_NoSystemPropertySet() {
        // Arrange
        System.clearProperty("llm.judge.base-url");

        // Act
        String url = LlmJudgeReachability.resolveBaseUrl();

        // Assert
        assertThat(url).isEqualTo("http://172.22.22.63:1234/v1");
    }

    @Test
    void Should_ReturnOverriddenUrl_When_SystemPropertyIsSet() {
        // Arrange
        System.setProperty("llm.judge.base-url", "http://localhost:8080/v1");

        // Act
        String url = LlmJudgeReachability.resolveBaseUrl();

        // Assert
        assertThat(url).isEqualTo("http://localhost:8080/v1");
    }

    // ========================== FINETUNE_BLACKLIST completeness ==========================

    @Test
    void Should_ContainAllFiveBlacklistMarkers_When_ConstantIsChecked() {
        // This test documents the exact spec contract from §2.3 and §8.4
        assertThat(LlmJudgeReachability.FINETUNE_BLACKLIST)
            .as("Spec §2.3 defines exactly 5 blacklist markers")
            .containsExactlyInAnyOrder("uncensored", "heretic", "distilled", "aggressive", "finetune");
    }
}
