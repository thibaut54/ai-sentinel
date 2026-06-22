package pro.softcom.aisentinel.integration.bench;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Minimal OpenAI-compatible chat client for generative PII extractors. Sends the
 * document text and parses the model's JSON array of {@code {text,label}}
 * entities (tolerant to prose/markdown wrapping and to a few key spellings).
 */
final class LlmExtractorClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;
    private final int maxTokens;
    private final boolean jsonSchema;

    LlmExtractorClient(Duration timeout, int maxTokens, boolean jsonSchema) {
        // HTTP/1.1: HttpClient defaults to HTTP/2 and opens with an h2c upgrade
        // over cleartext. LM Studio's embedded server accepts the TCP connection
        // but never answers that upgrade, so every request hangs until the request
        // timeout (the connection succeeds, hence it is not a connect failure).
        // Forcing HTTP/1.1 makes it respond immediately, like curl does.
        // NO_PROXY: the benchmark endpoints are always local / LAN (LM Studio,
        // Ollama…) and must never be routed through the corporate proxy.
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();
        this.maxTokens = maxTokens;
        this.jsonSchema = jsonSchema;
    }

    /** A raw entity returned by the model (value + label), pre-canonicalisation. */
    record RawEntity(String value, String label) {
    }

    /** @param jsonArrayFound false when the response had no parseable JSON array. */
    record ExtractResult(List<RawEntity> entities, boolean jsonArrayFound) {
    }

    /**
     * Lists the model ids served at {@code baseUrl} (GET {@code /models}). Used as
     * a fast reachability preflight: a dead endpoint or a mis-typed model id (e.g.
     * a missing {@code @quant} suffix) shows up in seconds instead of after a
     * 10-minute warmup timeout.
     */
    Set<String> listModelIds(String baseUrl, Duration timeout) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.replaceAll("/+$", "") + "/models"))
            .timeout(timeout)
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("models HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode n : MAPPER.readTree(resp.body()).path("data")) {
            String id = n.path("id").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return ids;
    }

    ExtractResult extract(ExtractorModel m, String text, Duration requestTimeout)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(m.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(buildBody(m, text)))
            .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("extractor HTTP " + resp.statusCode() + ": " + truncate(resp.body()));
        }
        String content = extractContent(resp.body());
        String array = firstJsonArray(content);
        if (array == null) {
            return new ExtractResult(List.of(), false);
        }
        return new ExtractResult(parseEntities(array), true);
    }

    private String buildBody(ExtractorModel m, String text) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", m.model());
        body.put("temperature", 0);
        body.put("stream", false);
        body.put("max_tokens", maxTokens);
        if (jsonSchema) {
            body.set("response_format", entityArraySchema());
        }
        ArrayNode messages = body.putArray("messages");
        if (m.systemPrompt() != null && !m.systemPrompt().isBlank()) {
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", m.systemPrompt());
        }
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", text);
        return body.toString();
    }

    /**
     * OpenAI-compatible {@code response_format} forcing the model to emit a bare
     * JSON array of {@code {text,label}} objects. Grammar-constrained decoding
     * removes free-form prose/markdown and the chaotic object shapes some
     * extractors emit (keyed-by-id maps, field→value maps), which otherwise show
     * up as "NO JSON ARRAY" parse failures. It constrains the response shape
     * only — hallucinated values still count against the model, so the benchmark
     * stays faithful.
     */
    private static ObjectNode entityArraySchema() {
        ObjectNode props = MAPPER.createObjectNode();
        props.putObject("text").put("type", "string");
        props.putObject("label").put("type", "string");

        ObjectNode item = MAPPER.createObjectNode();
        item.put("type", "object");
        item.set("properties", props);
        item.putArray("required").add("text").add("label");

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "array");
        schema.set("items", item);

        ObjectNode jsonSchemaNode = MAPPER.createObjectNode();
        jsonSchemaNode.put("name", "pii_entities");
        jsonSchemaNode.put("strict", true);
        jsonSchemaNode.set("schema", schema);

        ObjectNode responseFormat = MAPPER.createObjectNode();
        responseFormat.put("type", "json_schema");
        responseFormat.set("json_schema", jsonSchemaNode);
        return responseFormat;
    }

    private static String extractContent(String responseBody) throws IOException {
        JsonNode message = MAPPER.readTree(responseBody).path("choices").path(0).path("message");
        String content = message.path("content").asText("");
        if (content.isBlank()) {
            content = message.path("reasoning_content").asText("");
        }
        return content;
    }

    /** First balanced top-level JSON array substring, or null. Quote-aware. */
    static String firstJsonArray(String s) {
        int start = s.indexOf('[');
        while (start >= 0) {
            int depth = 0;
            boolean inStr = false;
            boolean esc = false;
            for (int i = start; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inStr) {
                    if (esc) {
                        esc = false;
                    } else if (c == '\\') {
                        esc = true;
                    } else if (c == '"') {
                        inStr = false;
                    }
                    continue;
                }
                if (c == '"') {
                    inStr = true;
                } else if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        return s.substring(start, i + 1);
                    }
                }
            }
            start = s.indexOf('[', start + 1);
        }
        return null;
    }

    private static List<RawEntity> parseEntities(String array) throws IOException {
        List<RawEntity> entities = new ArrayList<>();
        JsonNode arr = MAPPER.readTree(array);
        if (!arr.isArray()) {
            return entities;
        }
        for (JsonNode e : arr) {
            if (!e.isObject()) {
                continue;
            }
            String value = firstText(e, "text", "value", "entity", "span");
            String label = firstText(e, "label", "type", "entity_type", "category");
            if (value != null && !value.isBlank() && label != null && !label.isBlank()) {
                entities.add(new RawEntity(value, label));
            }
        }
        return entities;
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
