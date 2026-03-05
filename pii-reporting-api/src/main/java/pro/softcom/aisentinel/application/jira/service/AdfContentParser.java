package pro.softcom.aisentinel.application.jira.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Set;

/**
 * Parses Atlassian Document Format (ADF) JSON to plain text.
 * <p>
 * ADF structure: {@code {type:"doc", content:[{type:"paragraph", content:[{type:"text", text:"..."}]}]}}
 * <p>
 * Recursively walks the JSON tree, extracting text nodes and joining blocks with newlines.
 */
public class AdfContentParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> BLOCK_NODES = Set.of(
        "paragraph", "heading", "bulletList", "orderedList", "listItem",
        "table", "tableRow", "tableCell", "tableHeader",
        "blockquote", "codeBlock", "panel", "rule"
    );

    private static final Set<String> IGNORED_NODES = Set.of(
        "mediaGroup", "mediaSingle", "media"
    );

    /**
     * Parse an ADF JSON string to plain text.
     *
     * @param adfJson the ADF JSON string
     * @return plain text extracted from the ADF document, or empty string if input is null/blank/unparseable
     */
    public String toPlainText(String adfJson) {
        if (adfJson == null || adfJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(adfJson);
            return extractText(root).trim();
        } catch (Exception _) {
            return "";
        }
    }

    private String extractText(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return "";
        }

        String type = node.has("type") ? node.get("type").asText() : "";

        if (IGNORED_NODES.contains(type)) {
            return "";
        }

        return switch (type) {
            case "text" -> extractTextNode(node);
            case "mention" -> extractMentionNode(node);
            case "emoji" -> extractEmojiNode(node);
            case "hardBreak" -> "\n";
            default -> extractChildContent(node, type);
        };
    }

    private String extractTextNode(JsonNode node) {
        return node.has("text") ? node.get("text").asText() : "";
    }

    private String extractMentionNode(JsonNode node) {
        if (node.has("attrs") && node.get("attrs").has("text")) {
            return node.get("attrs").get("text").asText();
        }
        return "";
    }

    private String extractEmojiNode(JsonNode node) {
        if (node.has("attrs") && node.get("attrs").has("shortName")) {
            return node.get("attrs").get("shortName").asText();
        }
        return "";
    }

    private String extractChildContent(JsonNode node, String type) {
        if (!node.has("content") || !node.get("content").isArray()) {
            return BLOCK_NODES.contains(type) ? "\n" : "";
        }

        var sb = new StringBuilder();
        for (JsonNode child : node.get("content")) {
            sb.append(extractText(child));
        }

        if (BLOCK_NODES.contains(type)) {
            sb.append("\n");
        }

        return sb.toString();
    }
}
