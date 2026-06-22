package pro.softcom.aisentinel.integration.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

/**
 * Maps a detector's emitted {@code pii_type} to the benchmark's canonical
 * concept. Source of truth is {@code label_mapping.toml} ([detectors.*]),
 * materialised by {@code build_datasets.py} into
 * {@code mappings/detector_concept_map.json}:
 *
 * <pre>{ "GLINER2": {"PAYMENT_CARD": "CARD_NUMBER", ...}, "PRESIDIO": {...}, ... }</pre>
 *
 * <p>An emitted type with no mapping falls back to its own normalised name, so a
 * detector that returns an unmapped type is still scored as a distinct concept
 * (it simply won't match any gold span unless a dataset uses the same name).
 */
public final class ConceptMap {

    private final Map<DetectorSource, Map<String, String>> bySource;

    private ConceptMap(Map<DetectorSource, Map<String, String>> bySource) {
        this.bySource = bySource;
    }

    static ConceptMap of(Map<DetectorSource, Map<String, String>> bySource) {
        return new ConceptMap(new HashMap<>(bySource));
    }

    public static ConceptMap load(Path json) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(json));
        Map<DetectorSource, Map<String, String>> bySource = new HashMap<>();
        root.fieldNames().forEachRemaining(detectorName -> {
            DetectorSource source = parseSource(detectorName);
            if (source == null) {
                return;
            }
            Map<String, String> typeMap = new HashMap<>();
            JsonNode entries = root.get(detectorName);
            entries.fieldNames().forEachRemaining(t ->
                typeMap.put(normalize(t), entries.get(t).asText()));
            bySource.put(source, typeMap);
        });
        return new ConceptMap(bySource);
    }

    /** Canonical concept for an emitted (source, type), never null. */
    String canonical(DetectorSource source, String emittedType) {
        String key = normalize(emittedType);
        Map<String, String> typeMap = bySource.get(source);
        if (typeMap != null) {
            String mapped = typeMap.get(key);
            if (mapped != null) {
                return mapped;
            }
        }
        return key;
    }

    private static DetectorSource parseSource(String name) {
        try {
            return DetectorSource.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Same normalisation the gRPC adapter applies to {@code type()}. */
    private static String normalize(String type) {
        return type.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }
}
