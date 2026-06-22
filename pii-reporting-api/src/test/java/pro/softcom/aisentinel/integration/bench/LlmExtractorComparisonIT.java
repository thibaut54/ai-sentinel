package pro.softcom.aisentinel.integration.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import pro.softcom.aisentinel.integration.bench.LlmExtractorClient.ExtractResult;
import pro.softcom.aisentinel.integration.bench.LlmExtractorClient.RawEntity;

/**
 * Compares generative LLM PII <em>extractors</em> head-to-head (e.g.
 * {@code OpenMed/Ministral-3B-PII-Preview} vs {@code detect-pii-4b-v2} run in
 * extraction mode) on the same labelled gold, scored value-level by
 * {@link ValueScorer}. No Docker / Spring — it only needs the gold files and a
 * reachable OpenAI-compatible endpoint per model.
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Gold built (see {@code benchmarks/pii-dataset-eval/README.md}).</li>
 *   <li>{@code extractors.json} listing the models + endpoints (copy
 *       {@code extractors.sample.json}).</li>
 * </ol>
 *
 * <h2>Run</h2>
 * <pre>
 *   $env:RUN_LLM_EXTRACTOR_BENCHMARK = "true"
 *   mvn -pl pii-reporting-api -Dtest=LlmExtractorComparisonIT test
 * </pre>
 * Properties: {@code corpus.bench.gold-dir}, {@code bench.extractors-config},
 * {@code bench.extractor-concept-map}, {@code corpus.bench.max-docs},
 * {@code bench.extractor.request-timeout-s} (default 120),
 * {@code bench.extractor.max-tokens} (default 2048),
 * {@code bench.extractor.json-schema} (default true) — sends an OpenAI
 * {@code response_format} json_schema so endpoints that support grammar-
 * constrained decoding always return a parseable array; set false for backends
 * that reject it.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LLM_EXTRACTOR_BENCHMARK", matches = "true")
class LlmExtractorComparisonIT {

    private static final Logger log = LoggerFactory.getLogger(LlmExtractorComparisonIT.class);
    private static final String OUTPUT_ROOT = "target/pii-bench";
    /** Short timeout for the GET /models reachability preflight. */
    private static final Duration PREFLIGHT_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void compareExtractors() throws Exception {
        Path goldDir = Paths.get(System.getProperty(
            "corpus.bench.gold-dir", "../benchmarks/pii-dataset-eval/gold")).toAbsolutePath();
        Path conceptMapPath = Paths.get(System.getProperty(
            "bench.extractor-concept-map",
            "../benchmarks/pii-dataset-eval/mappings/extractor_concept_map.json")).toAbsolutePath();
        Path configPath = Paths.get(System.getProperty(
            "bench.extractors-config", "../benchmarks/pii-dataset-eval/extractors.json")).toAbsolutePath();

        assertThat(Files.isDirectory(goldDir))
            .as("gold dir %s missing — build it first (benchmarks/pii-dataset-eval/README.md)", goldDir)
            .isTrue();
        assertThat(Files.isRegularFile(configPath))
            .as("extractors config %s missing — copy extractors.sample.json", configPath)
            .isTrue();

        List<GoldDoc> gold = loadGold(goldDir);
        assertThat(gold).as("gold dataset is empty").isNotEmpty();
        List<ExtractorModel> models = ExtractorModel.loadAll(configPath);
        assertThat(models).as("no models configured in %s", configPath).isNotEmpty();

        int requestTimeoutS = intProp("bench.extractor.request-timeout-s", 120);
        int maxTokens = intProp("bench.extractor.max-tokens", 2048);
        boolean jsonSchema = Boolean.parseBoolean(System.getProperty("bench.extractor.json-schema", "true"));
        LlmExtractorClient client = new LlmExtractorClient(Duration.ofSeconds(30), maxTokens, jsonSchema);
        Duration requestTimeout = Duration.ofSeconds(requestTimeoutS);

        log.info("[extractor-bench] config: {} model(s), {} gold docs, requestTimeout={}s, maxTokens={}, "
                + "jsonSchema={}, output={}",
            models.size(), gold.size(), requestTimeoutS, maxTokens, jsonSchema,
            Paths.get(OUTPUT_ROOT).toAbsolutePath());
        probeEndpoints(client, models);
        Set<String> ready = warmUpAndEstimate(client, models, gold, requestTimeout);

        Instant start = Instant.now();
        List<ExtractorReport.ModelEval> evals = new ArrayList<>();
        for (ExtractorModel model : models) {
            if (!ready.contains(model.name())) {
                log.warn("[extractor-bench] {} SKIPPED — endpoint/warmup unavailable (see preflight logs above)",
                    model.name());
                continue;
            }
            evals.add(evaluate(client, model, gold, conceptMapPath, requestTimeout));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("gold_docs", gold.size());
        meta.put("models", models.stream().map(ExtractorModel::name).toList());
        meta.put("metric", "value-level (canonical + normalised value)");
        meta.put("duration_s", Duration.between(start, Instant.now()).toSeconds());

        Path outDir = Paths.get(OUTPUT_ROOT).toAbsolutePath();
        ExtractorReport.write(outDir, evals, meta);
        log.info("[extractor-bench] report written to {}", outDir);
        assertThat(outDir.resolve("extractor-comparison.md")).exists();

        int totalPredictions = evals.stream()
            .mapToInt(e -> e.score().strictOverall().tp() + e.score().strictOverall().fp())
            .sum();
        assertThat(totalPredictions)
            .as("no extractor produced any in-scope prediction across the gold set — check endpoints, "
                + "model ids and the [extractors] label map (unknown labels are logged in the report)")
            .isPositive();
    }

    private ExtractorReport.ModelEval evaluate(LlmExtractorClient client, ExtractorModel model,
                                               List<GoldDoc> gold, Path conceptMapPath, Duration requestTimeout)
            throws IOException {
        // Fresh map per model so unknownLabels() is attributed to this model.
        ExtractorConceptMap conceptMap = ExtractorConceptMap.load(conceptMapPath);
        Map<String, List<Prediction>> predsByDoc = new LinkedHashMap<>();
        int rawEntities = 0;
        int dropped = 0;
        int parseFailures = 0;
        int httpFailures = 0;

        log.info("[extractor-bench] === model {} ({}) over {} docs ===",
            model.name(), model.model(), gold.size());
        // Models are warmed up once in warmUpAndEstimate() before this loop, so no
        // per-model warmup here: the endpoint is already known reachable and loaded.
        Instant loopStart = Instant.now();
        int idx = 0;
        for (GoldDoc doc : gold) {
            idx++;
            Instant tDoc = Instant.now();
            ExtractResult result = extractWithRetry(client, model, doc, requestTimeout);
            if (result == null) {
                httpFailures++;
                log.warn("[extractor-bench] {} [{}/{}] {} : HTTP FAIL ({}s) | ETA ~{}", model.name(), idx,
                    gold.size(), doc.id(), Duration.between(tDoc, Instant.now()).toSeconds(),
                    etaFor(loopStart, idx, gold.size()));
                continue;
            }
            if (!result.jsonArrayFound()) {
                parseFailures++;
            }
            List<Prediction> preds = new ArrayList<>();
            for (RawEntity raw : result.entities()) {
                rawEntities++;
                String canonical = conceptMap.canonical(model.name(), raw.label());
                if (canonical == null) {
                    dropped++;
                } else {
                    preds.add(new Prediction(canonical, raw.value()));
                }
            }
            predsByDoc.put(doc.id(), preds);
            log.info("[extractor-bench] {} [{}/{}] {} : {} raw -> {} in-scope ({}s){} | ETA ~{}",
                model.name(), idx, gold.size(), doc.id(), result.entities().size(), preds.size(),
                Duration.between(tDoc, Instant.now()).toSeconds(),
                result.jsonArrayFound() ? "" : " [NO JSON ARRAY]", etaFor(loopStart, idx, gold.size()));
        }

        ScoreResult score = ValueScorer.score(gold, predsByDoc);
        log.info("[extractor-bench] {} : F1={} P={} R={} raw={} dropped={} parseFail={} httpFail={} unknown={}",
            model.name(), score.strictOverall().f1(), score.strictOverall().precision(),
            score.strictOverall().recall(), rawEntities, dropped, parseFailures, httpFailures,
            conceptMap.unknownLabels());
        return new ExtractorReport.ModelEval(model.name(), model.model(), score, gold.size(),
            rawEntities, dropped, parseFailures, httpFailures, conceptMap.unknownLabels());
    }

    /**
     * Fast reachability preflight: lists the models served at each distinct
     * endpoint (GET {@code /models}) so a dead endpoint or a mis-typed model id
     * (e.g. a missing {@code @quant} suffix) is obvious in seconds, instead of
     * surfacing only after a 10-minute warmup timeout. Purely informational — it
     * never fails the test (LM Studio may still JIT-load a model on first use).
     */
    private void probeEndpoints(LlmExtractorClient client, List<ExtractorModel> models) {
        Map<String, Set<String>> servedByUrl = new LinkedHashMap<>();
        for (ExtractorModel m : models) {
            Set<String> served = servedByUrl.computeIfAbsent(m.baseUrl(), url -> probeOne(client, url));
            if (!served.isEmpty() && !served.contains(m.model())) {
                log.warn("[extractor-bench] model id '{}' ({}) NOT in the served list {} — check the "
                    + "@quant suffix; the id must match exactly or the endpoint will time out",
                    m.model(), m.name(), served);
            }
        }
    }

    private Set<String> probeOne(LlmExtractorClient client, String baseUrl) {
        try {
            Set<String> ids = client.listModelIds(baseUrl, PREFLIGHT_TIMEOUT);
            log.info("[extractor-bench] endpoint {} reachable, serves {} model(s): {}", baseUrl, ids.size(), ids);
            return ids;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Set.of();
        } catch (IOException e) {
            log.warn("[extractor-bench] endpoint {} preflight FAILED: {} — model(s) on it will likely time out",
                baseUrl, e.toString());
            return Set.of();
        }
    }

    /**
     * Warms up each model (forcing the lazy load) and probes the single shortest
     * gold doc to project the full run duration <em>before</em> committing to it.
     * Deliberately cheap — one warmup plus one short inference per model — so the
     * estimate costs seconds, not the whole run. Models whose warmup or probe
     * fails are excluded: their docs are skipped rather than retried for hours.
     *
     * @return the names of the models that are ready to be evaluated
     */
    private Set<String> warmUpAndEstimate(LlmExtractorClient client, List<ExtractorModel> models,
                                          List<GoldDoc> gold, Duration requestTimeout) {
        GoldDoc probe = gold.stream()
            .min(Comparator.comparingInt(d -> d.text().length()))
            .orElseThrow();
        Set<String> ready = new LinkedHashSet<>();
        long totalEtaS = 0;
        for (ExtractorModel model : models) {
            if (!warmup(client, model, requestTimeout)) {
                log.warn("[extractor-bench] {} not ready (warmup failed) — its {} docs will be skipped",
                    model.name(), gold.size());
                continue;
            }
            Instant tProbe = Instant.now();
            ExtractResult r = extractWithRetry(client, model, probe, requestTimeout);
            long probeMs = Duration.between(tProbe, Instant.now()).toMillis();
            if (r == null) {
                log.warn("[extractor-bench] {} not ready (probe doc {} failed) — its {} docs will be skipped",
                    model.name(), probe.id(), gold.size());
                continue;
            }
            ready.add(model.name());
            long modelEtaS = Math.round(probeMs / 1000.0 * gold.size());
            totalEtaS += modelEtaS;
            log.info("[extractor-bench] {} estimate: shortest doc {} ({} chars) took {}ms -> ~{} for {} docs",
                model.name(), probe.id(), probe.text().length(), probeMs, humanDuration(modelEtaS), gold.size());
        }
        log.info("[extractor-bench] >>> ESTIMATED TOTAL RUN TIME for {}/{} ready model(s): ~{} "
            + "(lower bound — based on the shortest doc; longer docs push this up) <<<",
            ready.size(), models.size(), humanDuration(totalEtaS));
        return ready;
    }

    /**
     * Forces the model to load. The first inference on a cold endpoint (LM Studio
     * loads the model lazily) can take minutes, far longer than the per-doc
     * request timeout, so the warmup uses a generous timeout to absorb the load
     * once.
     *
     * @return true if the model responded, false if it timed out or errored
     */
    private boolean warmup(LlmExtractorClient client, ExtractorModel model, Duration requestTimeout) {
        Duration warmupTimeout = requestTimeout.compareTo(Duration.ofMinutes(10)) < 0
            ? Duration.ofMinutes(10) : requestTimeout;
        Instant t0 = Instant.now();
        log.info("[extractor-bench] {} warming up (loading model, can take a while on first call)...",
            model.name());
        try {
            ExtractResult r = client.extract(model,
                "Warmup: email a@b.io, IBAN CH9300762011623852957.", warmupTimeout);
            log.info("[extractor-bench] {} warmup OK in {}s ({} entities, jsonArray={})",
                model.name(), Duration.between(t0, Instant.now()).toSeconds(),
                r.entities().size(), r.jsonArrayFound());
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            log.warn("[extractor-bench] {} warmup failed in {}s: {} — raise -Dbench.extractor.request-timeout-s",
                model.name(), Duration.between(t0, Instant.now()).toSeconds(), e.toString());
            return false;
        }
    }

    /** Projected remaining time given {@code done} of {@code total} docs since {@code loopStart}. */
    private static String etaFor(Instant loopStart, int done, int total) {
        if (done <= 0 || done >= total) {
            return "0s";
        }
        long elapsedS = Duration.between(loopStart, Instant.now()).toSeconds();
        return humanDuration(Math.round((double) elapsedS / done * (total - done)));
    }

    private static String humanDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        long m = seconds / 60;
        long s = seconds % 60;
        return s == 0 ? m + "m" : m + "m " + s + "s";
    }

    /** 2 attempts; null when both fail (counts as an HTTP failure for the doc). */
    private ExtractResult extractWithRetry(LlmExtractorClient client, ExtractorModel model, GoldDoc doc,
                                           Duration requestTimeout) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return client.extract(model, doc.text(), requestTimeout);
            } catch (IOException e) {
                if (attempt == 2) {
                    log.warn("[extractor-bench] {} doc {} failed: {}", model.name(), doc.id(), e.toString());
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private List<GoldDoc> loadGold(Path goldDir) throws IOException {
        List<GoldDoc> all = GoldDataset.loadDir(goldDir);
        Integer maxDocs = intPropOrNull("corpus.bench.max-docs");
        if (maxDocs != null && maxDocs > 0 && all.size() > maxDocs) {
            return all.subList(0, maxDocs);
        }
        return all;
    }

    private static int intProp(String key, int def) {
        Integer v = intPropOrNull(key);
        return v == null ? def : v;
    }

    private static Integer intPropOrNull(String key) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? null : Integer.parseInt(v);
    }
}
