package pro.softcom.aisentinel.integration.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads the JSONL gold files produced by {@code build_datasets.py}. One JSON
 * object per line:
 *
 * <pre>
 * {"id":"gretelai-0001","dataset":"gretelai","lang":"English",
 *  "text":"...","spans":[{"start":10,"end":31,"label":"IBAN"}],
 *  "ignore_spans":[{"start":40,"end":48,"src_label":"email"}]}
 * </pre>
 */
public final class GoldDataset {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GoldDataset() {
    }

    /** Loads and concatenates every {@code *.jsonl} gold file under {@code dir}. */
    public static List<GoldDoc> loadDir(Path dir) throws IOException {
        List<GoldDoc> docs = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            List<Path> files = stream
                .filter(p -> p.getFileName().toString().endsWith(".jsonl"))
                .sorted()
                .toList();
            for (Path f : files) {
                docs.addAll(load(f));
            }
        }
        return docs;
    }

    static List<GoldDoc> load(Path jsonl) throws IOException {
        List<GoldDoc> docs = new ArrayList<>();
        for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            docs.add(parse(MAPPER.readTree(line)));
        }
        return docs;
    }

    private static GoldDoc parse(JsonNode n) {
        return new GoldDoc(
            n.path("id").asText(),
            n.path("dataset").asText(),
            n.path("lang").asText(""),
            n.path("text").asText(),
            parseSpans(n.path("spans"), "label"),
            parseSpans(n.path("ignore_spans"), "src_label")
        );
    }

    private static List<GoldSpan> parseSpans(JsonNode arr, String labelField) {
        List<GoldSpan> spans = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode s : arr) {
                spans.add(new GoldSpan(
                    s.path("start").asInt(),
                    s.path("end").asInt(),
                    s.path(labelField).asText("")));
            }
        }
        return spans;
    }
}
