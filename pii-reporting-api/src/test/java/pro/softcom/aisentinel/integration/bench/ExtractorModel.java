package pro.softcom.aisentinel.integration.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Configuration of one generative LLM extractor to compare. Loaded from a JSON
 * array (default {@code benchmarks/pii-dataset-eval/extractors.json}):
 *
 * <pre>
 * [
 *   {"name":"ministral-3b-pii","baseUrl":"http://host.docker.internal:1234/v1",
 *    "model":"OpenMed/Ministral-3B-PII-Preview","systemPrompt":null},
 *   {"name":"detect-pii-4b-v2","baseUrl":"http://host.docker.internal:1234/v1",
 *    "model":"detect-pii-4b-v2","systemPrompt":"Extract all PII as a JSON array of {text,label}."}
 * ]
 * </pre>
 *
 * @param name         display/file-safe id of the model under test
 * @param baseUrl      OpenAI-compatible base URL (…/v1)
 * @param model        model id as served by the endpoint
 * @param systemPrompt optional system prompt; null/blank when the model bakes
 *                     its PII instruction into the chat template (e.g. Ministral)
 */
public record ExtractorModel(String name, String baseUrl, String model, String systemPrompt) {

    static List<ExtractorModel> loadAll(Path json) throws IOException {
        JsonNode arr = new ObjectMapper().readTree(Files.readString(json));
        List<ExtractorModel> models = new ArrayList<>();
        for (JsonNode n : arr) {
            models.add(new ExtractorModel(
                n.path("name").asText(),
                n.path("baseUrl").asText(),
                n.path("model").asText(),
                n.hasNonNull("systemPrompt") ? n.get("systemPrompt").asText() : null));
        }
        return models;
    }
}
