package pro.softcom.aisentinel.integration.bench;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the tolerant JSON-array extraction from a model's free-form
 * chat content. No network.
 */
class LlmExtractorClientTest {

    @Test
    void Should_ReturnArray_When_ContentIsPlainArray() {
        String out = LlmExtractorClient.firstJsonArray("[{\"text\":\"a\",\"label\":\"email\"}]");
        assertThat(out).isEqualTo("[{\"text\":\"a\",\"label\":\"email\"}]");
    }

    @Test
    void Should_ExtractArray_When_WrappedInProseAndMarkdown() {
        String content = "Sure! Here are the entities:\n```json\n[{\"text\":\"x\",\"label\":\"iban\"}]\n```\nDone.";
        String out = LlmExtractorClient.firstJsonArray(content);
        assertThat(out).isEqualTo("[{\"text\":\"x\",\"label\":\"iban\"}]");
    }

    @Test
    void Should_RespectBracketsInsideStrings_When_ValueContainsBrackets() {
        String content = "[{\"text\":\"a[b]c\",\"label\":\"secret\"}]";
        String out = LlmExtractorClient.firstJsonArray(content);
        assertThat(out).isEqualTo(content);
    }

    @Test
    void Should_HandleNestedArrays_When_Balanced() {
        String content = "noise [[1,2],[3,4]] tail";
        String out = LlmExtractorClient.firstJsonArray(content);
        assertThat(out).isEqualTo("[[1,2],[3,4]]");
    }

    @Test
    void Should_ReturnNull_When_NoArrayPresent() {
        assertThat(LlmExtractorClient.firstJsonArray("no array here")).isNull();
    }
}
