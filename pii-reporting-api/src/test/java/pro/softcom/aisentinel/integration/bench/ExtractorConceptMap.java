package pro.softcom.aisentinel.integration.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Maps a generative extractor's emitted label to the benchmark's canonical
 * concept. Source of truth is {@code label_mapping.toml} ([extractors.*]),
 * materialised into {@code mappings/extractor_concept_map.json}:
 *
 * <pre>{ "_default": {"iban":"IBAN","email":"IGNORE",...}, "&lt;model&gt;": {...} }</pre>
 *
 * <p>Resolution: a per-model section overrides {@code _default}. A label mapped
 * to {@code "IGNORE"} (or absent) resolves to {@code null} — the prediction is
 * dropped (real but out-of-scope PII is neither rewarded nor penalised,
 * symmetric to the dataset ignore zones). Labels absent from the table are
 * recorded in {@link #unknownLabels()} so they can surface in the report
 * instead of being silently ignored.
 */
final class ExtractorConceptMap {

    static final String IGNORE = "IGNORE";

    private final Map<String, Map<String, String>> byModel;
    private final Set<String> unknownLabels = new TreeSet<>();

    private ExtractorConceptMap(Map<String, Map<String, String>> byModel) {
        this.byModel = byModel;
    }

    static ExtractorConceptMap of(Map<String, Map<String, String>> byModel) {
        return new ExtractorConceptMap(new HashMap<>(byModel));
    }

    static ExtractorConceptMap load(Path json) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(json));
        Map<String, Map<String, String>> byModel = new HashMap<>();
        root.fieldNames().forEachRemaining(model -> {
            Map<String, String> labelMap = new HashMap<>();
            JsonNode entries = root.get(model);
            entries.fieldNames().forEachRemaining(label ->
                labelMap.put(normalize(label), entries.get(label).asText()));
            byModel.put(model, labelMap);
        });
        return new ExtractorConceptMap(byModel);
    }

    /**
     * Canonical concept for a model's emitted label, or {@code null} when the
     * label is out of scope (IGNORE) or unknown. Unknown labels are recorded.
     */
    String canonical(String modelId, String label) {
        String key = normalize(label);
        String mapped = lookup(modelId, key);
        if (mapped == null) {
            unknownLabels.add(key);
            return null;
        }
        return IGNORE.equals(mapped) ? null : mapped;
    }

    private String lookup(String modelId, String key) {
        Map<String, String> modelMap = byModel.get(modelId);
        if (modelMap != null && modelMap.containsKey(key)) {
            return modelMap.get(key);
        }
        Map<String, String> def = byModel.get("_default");
        return def == null ? null : def.get(key);
    }

    /** Distinct labels seen at runtime that were absent from the table. */
    Set<String> unknownLabels() {
        return unknownLabels;
    }

    private static String normalize(String label) {
        return label.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }
}
