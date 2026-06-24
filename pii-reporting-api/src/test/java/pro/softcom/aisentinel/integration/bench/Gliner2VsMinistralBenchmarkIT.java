package pro.softcom.aisentinel.integration.bench;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pro.softcom.aisentinel.AiSentinelApplication;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.SensitiveData;
import pro.softcom.aisentinel.integration.bench.LlmExtractorClient.ExtractResult;
import pro.softcom.aisentinel.integration.bench.LlmExtractorClient.RawEntity;

/**
 * Head-to-head benchmark answering one question: <em>can the production GLINER2
 * detector be replaced by the generative {@code ministral-3b-pii-preview@q8_0}
 * extractor?</em> It scores <strong>both models on the same labelled gold with the
 * same value-level metric</strong> ({@link ValueScorer}: canonical concept +
 * normalised value) so their precision/recall/F1 are directly comparable.
 *
 * <h2>Why value-level (and why it is fair)</h2>
 * GLINER2 returns char offsets, the LLM returns values, so the only common ground
 * is value-level matching:
 * <ul>
 *   <li><b>GLINER2</b> runs in isolation inside the same containerised pipeline as
 *       production (Postgres + the Python {@code pii-detector} over gRPC). Seeded
 *       to detect only its configured, in-scope concepts, each detection becomes a
 *       {@link Prediction} ({@code canonical} via {@link ConceptMap},
 *       {@code value} = {@link SensitiveData#value()}). The <b>judge is disabled</b>
 *       so this measures GLINER2's <em>raw</em> output — symmetric to the LLM,
 *       which is also unfiltered — and removes any external judge dependency.</li>
 *   <li><b>ministral</b> is called over an OpenAI-compatible endpoint (LM Studio)
 *       from the host JVM ({@link LlmExtractorClient}); its emitted labels map via
 *       {@link ExtractorConceptMap}, which drops out-of-scope concepts (email,
 *       names, dates → {@code IGNORE}), symmetric to GLINER2 only ever emitting
 *       in-scope concepts. Out-of-scope PII is thus neither rewarded nor penalised
 *       on either side.</li>
 * </ul>
 * Both sets of predictions are scored against the same {@link GoldDoc} spans, and
 * {@link ExtractorReport} writes the side-by-side table plus the head-to-head Δ
 * ({@code ministral − GLINER2}, i.e. what replacing GLINER2 with ministral
 * gains/loses).
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Gold built: {@code python benchmarks/pii-dataset-eval/build_datasets.py}
 *       (see its README) — provides the gold + both concept maps.</li>
 *   <li>Docker, for the GLINER2 container.</li>
 *   <li>A reachable OpenAI-compatible endpoint serving the LLM. From the host JVM
 *       use {@code http://localhost:1234/v1} (LM Studio here) or the LAN IP,
 *       <b>not</b> {@code host.docker.internal} — the LLM call does not go through
 *       the container.</li>
 * </ol>
 *
 * <h2>Run</h2>
 * IntelliJ run configs under {@code .run/} (env {@code RUN_GLINER2_VS_MINISTRAL_BENCHMARK=true} preset):
 * <ul>
 *   <li>{@code Gliner2VsMinistral smoke} — only {@link #smokeBothModelsProduceInScopePredictions()};
 *       fastest way to validate the image build + gRPC wiring + the LLM endpoint on one short text.</li>
 *   <li>{@code Gliner2VsMinistral quick} — full class capped to a few docs ({@code corpus.bench.max-docs}).</li>
 *   <li>{@code Gliner2VsMinistral} — the real full-gold run.</li>
 * </ul>
 * Or via Maven:
 * <pre>
 *   $env:RUN_GLINER2_VS_MINISTRAL_BENCHMARK = "true"
 *   mvn -pl pii-reporting-api -Dtest=Gliner2VsMinistralBenchmarkIT `
 *       "-Dcorpus.bench.hf-cache=C:\hf-cache" `
 *       "-Dbench.ministral.base-url=http://localhost:1234/v1" test
 * </pre>
 * Useful system properties: {@code corpus.bench.gold-dir}, {@code corpus.bench.concept-map},
 * {@code bench.extractor-concept-map}, {@code corpus.bench.max-docs}, {@code corpus.bench.threshold},
 * {@code bench.ministral.base-url}, {@code bench.ministral.model} (default
 * {@code ministral-3b-pii-preview@q8_0}, mind the {@code @quant} suffix),
 * {@code bench.ministral.system-prompt}, {@code bench.extractor.request-timeout-s} (120),
 * {@code bench.extractor.max-tokens} (2048), {@code bench.extractor.json-schema} (true).
 */
@Testcontainers
@SpringBootTest(classes = AiSentinelApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "RUN_GLINER2_VS_MINISTRAL_BENCHMARK", matches = "true")
class Gliner2VsMinistralBenchmarkIT {

    private static final Logger log = LoggerFactory.getLogger(Gliner2VsMinistralBenchmarkIT.class);
    private static final Logger CONTAINER_LOG = LoggerFactory.getLogger("pii-detector-container");

    private static final int GRPC_PORT = 50051;
    private static final String POSTGRES_ALIAS = "postgres-it";
    private static final String DB_NAME = "ai-sentinel";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";

    private static final String MASTER_SEED = "classpath:sql/data-improved-gliner2-presidio-regex.sql";
    private static final String OUTPUT_ROOT = "target/pii-bench/gliner2-vs-ministral";

    /** Short reachability preflight for the LLM endpoint's GET /models. */
    private static final Duration PREFLIGHT_TIMEOUT = Duration.ofSeconds(10);
    /** First call to a cold LM Studio JIT-loads the model; absorb it once. */
    private static final Duration MIN_WARMUP_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Short synthetic text mixing PII types GLINER2 is configured for (IBAN, AVS,
     * card, IP, password) so the smoke test can assert both models produce an
     * in-scope prediction without paying the cost of the whole gold set.
     */
    private static final String SMOKE_TEXT = String.join("\n",
        "Coordonnees bancaires : IBAN CH9300762011623852957.",
        "Numero AVS : 756.3047.5009.62.",
        "Carte de credit : 4111 1111 1111 1111, CVV 123.",
        "Adresse IP du serveur : 192.168.1.42.",
        "Mot de passe utilisateur : MotDePasseSecret123!");

    private static final Path HF_CACHE_DIR = Paths.get(
        System.getProperty("corpus.bench.hf-cache",
            System.getProperty("user.home") + "/.ai-sentinel-it-hf-cache"));

    // LLM extractor under test (host JVM -> endpoint; localhost/LAN, NOT host.docker.internal).
    private static final String MINISTRAL_NAME = System.getProperty("bench.ministral.name", "ministral-3b-pii");
    private static final String MINISTRAL_BASE_URL =
        System.getProperty("bench.ministral.base-url", "http://localhost:1234/v1");
    private static final String MINISTRAL_MODEL =
        System.getProperty("bench.ministral.model", "ministral-3b-pii-preview@q8_0");
    /** null => the model bakes its PII instruction into its chat template (Ministral does). */
    private static final String MINISTRAL_SYSTEM_PROMPT = System.getProperty("bench.ministral.system-prompt", null);

    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USER)
            .withPassword(DB_PASSWORD)
            .withNetwork(NETWORK)
            .withNetworkAliases(POSTGRES_ALIAS);

    @Container
    static final GenericContainer<?> piiDetector = new GenericContainer<>(buildPiiDetectorImage())
        .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(""))
        .withCommand("python", "-m", "pii_detector.server", "--port", String.valueOf(GRPC_PORT))
        .withExposedPorts(GRPC_PORT)
        .withNetwork(NETWORK)
        .withNetworkAliases("pii-detector")
        .withExtraHost("host.docker.internal", "host-gateway")
        .withCreateContainerCmdModifier(cmd -> {
            HostConfig hc = cmd.getHostConfig() != null ? cmd.getHostConfig() : new HostConfig();
            hc.withBinds(new Bind(ensureHfCacheDir(), new Volume("/app/.cache/huggingface")));
            cmd.withHostConfig(hc);
        })
        .withEnv("HF_HOME", "/app/.cache/huggingface")
        .withEnv("TRANSFORMERS_CACHE", "/app/.cache/huggingface")
        .withEnv("DB_HOST", POSTGRES_ALIAS)
        .withEnv("DB_PORT", "5432")
        .withEnv("DB_NAME", DB_NAME)
        .withEnv("DB_USER", DB_USER)
        .withEnv("DB_PASSWORD", DB_PASSWORD)
        // No LLM_JUDGE_* env: the judge is disabled per scan via the DB flags
        // (seedGliner2Raw), so GLINER2 is measured raw and no judge endpoint is needed.
        .withLogConsumer(Gliner2VsMinistralBenchmarkIT::routeContainerLog)
        .waitingFor(Wait.forLogMessage(".*Server started on port.*", 1))
        .withStartupTimeout(Duration.ofMinutes(10));

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("pii-detector.host", piiDetector::getHost);
        registry.add("pii-detector.port", () -> piiDetector.getMappedPort(GRPC_PORT));
        registry.add("pii-detector.connection-timeout-ms", () -> "3600000");
        registry.add("pii-detector.request-timeout-ms", () -> "3600000");
        registry.add("PII_DATABASE_ENCRYPTION_KEY", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("PII_REPORTING_ALLOW_SECRET_REVEAL", () -> "false");
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PiiDetectorClient piiDetectorClient;

    /**
     * Smoke test (runs first, {@code @Order(0)}): validate <em>both</em> halves of
     * the harness on one short text so the whole comparison is known to work in
     * seconds, not after a multi-hour full run:
     * <ul>
     *   <li>GLINER2 — image build + Postgres reseed + gRPC wiring produce at least
     *       one in-scope prediction;</li>
     *   <li>ministral — the endpoint is reachable, the model loads, and it produces
     *       at least one in-scope prediction.</li>
     * </ul>
     * A failure here points at exactly one broken side (container vs endpoint).
     *
     * <p>Run isolated:
     * <pre>mvn -Dtest=Gliner2VsMinistralBenchmarkIT#smokeBothModelsProduceInScopePredictions ... test</pre>
     */
    @Test
    @Order(0)
    void smokeBothModelsProduceInScopePredictions() throws Exception {
        log.info("[smoke] === START ===");

        // --- GLINER2 side (container only, deterministic) ---
        seedGliner2Raw(null);
        ConceptMap detectorMap = ConceptMap.load(conceptMapPath());
        Instant t0 = Instant.now();
        ContentPiiDetection detection = piiDetectorClient.analyzeContent(SMOKE_TEXT);
        assertThat(detection).as("GLINER2 analyzeContent returned null").isNotNull();
        List<Prediction> gliner2Preds = gliner2Predictions(detection, detectorMap);
        log.info("[smoke] GLINER2 -> {} raw, {} in-scope predictions in {}s",
            detection.sensitiveDataFound().size(), gliner2Preds.size(),
            Duration.between(t0, Instant.now()).toSeconds());
        assertThat(gliner2Preds)
            .as("GLINER2 produced 0 in-scope predictions on the smoke text — the image build, the "
                + "GLINER2 model load, the gRPC wiring or the seed is broken")
            .isNotEmpty();

        // --- ministral side (endpoint) ---
        LlmExtractorClient client = newLlmClient();
        ExtractorModel model = ministralModel();
        ExtractorConceptMap extractorMap = ExtractorConceptMap.load(extractorMapPath());
        Duration requestTimeout = Duration.ofSeconds(intProp("bench.extractor.request-timeout-s", 120));
        probeAndWarmupMinistral(client, model, requestTimeout);

        ExtractResult result = extractWithRetry(client, model, SMOKE_TEXT, "smoke", requestTimeout);
        assertThat(result).as("ministral endpoint %s unreachable on the smoke text", model.baseUrl()).isNotNull();
        List<Prediction> ministralPreds = ministralPredictions(result.entities(), extractorMap, model.name());
        log.info("[smoke] ministral -> {} raw, {} in-scope predictions (jsonArray={})",
            result.entities().size(), ministralPreds.size(), result.jsonArrayFound());
        assertThat(ministralPreds)
            .as("ministral produced 0 in-scope predictions on the smoke text — check the model id "
                + "'%s' (@quant suffix) and the [extractors] label map", model.model())
            .isNotEmpty();

        log.info("[smoke] === DONE (both models produced in-scope predictions) ===");
    }

    /**
     * Full comparison: score GLINER2 (raw) and ministral over the gold set with the
     * same value-level metric, write the side-by-side report, and assert both
     * produced in-scope predictions (so a silently-empty side fails loudly).
     */
    @Test
    @Order(1)
    void compareGliner2VsMinistral() throws Exception {
        List<GoldDoc> gold = loadGold(goldDir());
        assertThat(gold)
            .as("gold dataset must be built first (see benchmarks/pii-dataset-eval/README.md)")
            .isNotEmpty();
        Double threshold = parseDoubleProperty("corpus.bench.threshold");
        log.info("[bench] {} gold docs, threshold={}, output={}",
            gold.size(), threshold == null ? "seed defaults" : threshold, Paths.get(OUTPUT_ROOT).toAbsolutePath());

        Instant start = Instant.now();
        ExtractorReport.ModelEval gliner2Eval = evaluateGliner2(gold, ConceptMap.load(conceptMapPath()), threshold);
        ExtractorReport.ModelEval ministralEval = evaluateMinistral(gold, extractorMapPath());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("comparison", "GLINER2 detector (raw, no judge) vs LLM extractor — value-level");
        meta.put("question", "can GLINER2 be replaced by " + MINISTRAL_MODEL + "?");
        meta.put("gold_docs", gold.size());
        meta.put("metric", "value-level (canonical concept + normalised value)");
        // Honest framing: GLINER2 is scored on the FULL in-scope gold, including
        // concepts no single detector owns alone in production (e.g. PHONE_NUMBER ->
        // REGEX, not GLINER2). Read the per-concept tables to compare on GLINER2's
        // own concepts. Out-of-scope PII (email, names, dates) is dropped on both
        // sides, so neither model is rewarded nor penalised for it.
        meta.put("scope_note", "GLINER2 scored on the full in-scope gold; some concepts "
            + "(e.g. PHONE_NUMBER) are covered by other detectors in prod — see per-concept tables");
        meta.put("ministral_endpoint", MINISTRAL_BASE_URL);
        meta.put("ministral_model", MINISTRAL_MODEL);
        meta.put("threshold_override", threshold == null ? "seed defaults" : threshold);
        meta.put("duration_s", Duration.between(start, Instant.now()).toSeconds());

        Path outDir = Paths.get(OUTPUT_ROOT).toAbsolutePath();
        // Order [GLINER2, ministral] so the head-to-head Δ reads "ministral − GLINER2".
        ExtractorReport.write(outDir, List.of(gliner2Eval, ministralEval), meta);
        log.info("[bench] === report written to {} ===", outDir);
        assertThat(outDir.resolve("extractor-comparison.md")).exists();

        assertThat(predictionCount(gliner2Eval))
            .as("GLINER2 produced no in-scope prediction across the gold set — check the container/seed")
            .isPositive();
        assertThat(predictionCount(ministralEval))
            .as("ministral produced no in-scope prediction across the gold set — check the endpoint, the "
                + "model id '%s' and the [extractors] label map (unknown labels logged in the report)",
                MINISTRAL_MODEL)
            .isPositive();
    }

    // ---- GLINER2 evaluation (container) --------------------------------------

    private ExtractorReport.ModelEval evaluateGliner2(List<GoldDoc> gold, ConceptMap detectorMap, Double threshold)
            throws SQLException {
        log.info("[bench] === GLINER2 (raw detector, judge OFF) over {} docs ===", gold.size());
        seedGliner2Raw(threshold);
        warmupGliner2();
        estimateGliner2(gold);

        Map<String, List<Prediction>> predsByDoc = new LinkedHashMap<>();
        int rawEntities = 0;
        int httpFailures = 0;
        Instant loopStart = Instant.now();
        int idx = 0;
        for (GoldDoc doc : gold) {
            idx++;
            Instant tDoc = Instant.now();
            ContentPiiDetection detection = analyzeWithRetry(doc.id(), doc.text());
            if (detection == null) {
                httpFailures++;
                log.warn("[bench] GLINER2 [{}/{}] {} : FAILED ({}s) | ETA ~{}", idx, gold.size(), doc.id(),
                    Duration.between(tDoc, Instant.now()).toSeconds(), etaFor(loopStart, idx, gold.size()));
                continue;
            }
            rawEntities += detection.sensitiveDataFound().size();
            List<Prediction> preds = gliner2Predictions(detection, detectorMap);
            predsByDoc.put(doc.id(), preds);
            log.info("[bench] GLINER2 [{}/{}] {} : {} raw -> {} in-scope ({}s) | ETA ~{}", idx, gold.size(),
                doc.id(), detection.sensitiveDataFound().size(), preds.size(),
                Duration.between(tDoc, Instant.now()).toSeconds(), etaFor(loopStart, idx, gold.size()));
        }

        ScoreResult score = ValueScorer.score(gold, predsByDoc);
        log.info("[bench] GLINER2 : F1={} P={} R={} raw={} httpFail={}", score.strictOverall().f1(),
            score.strictOverall().precision(), score.strictOverall().recall(), rawEntities, httpFailures);
        return new ExtractorReport.ModelEval("GLINER2", "gliner2 detector (raw, no judge)", score,
            gold.size(), rawEntities, 0, 0, httpFailures, Set.of());
    }

    /** Projects GLINER2 detections onto (canonical, value); skips the rare null/blank value. */
    private static List<Prediction> gliner2Predictions(ContentPiiDetection detection, ConceptMap detectorMap) {
        List<Prediction> preds = new ArrayList<>();
        for (SensitiveData sd : detection.sensitiveDataFound()) {
            if (sd.value() == null || sd.value().isBlank()) {
                continue;
            }
            preds.add(new Prediction(detectorMap.canonical(sd.source(), sd.type()), sd.value()));
        }
        return preds;
    }

    /**
     * Seeds GLINER2 alone with the judge OFF: {@code sensitiveDataFound()} is then
     * GLINER2's raw output (no judge drops, {@code prefilter_enabled=false}), the
     * fair counterpart to the unfiltered LLM extractor.
     */
    private void seedGliner2Raw(Double threshold) throws SQLException {
        resetAndReseedDb(MASTER_SEED);
        DetectorConfigSeed.apply(jdbcTemplate, BenchConfig.isolated(DetectorSource.GLINER2), threshold);
        jdbcTemplate.execute(
            "UPDATE pii_detection_config SET llm_judge_enabled = false, gliner2_judge_enabled = false WHERE id = 1");
    }

    private void warmupGliner2() {
        try {
            ContentPiiDetection warm = piiDetectorClient.analyzeContent(
                "Warmup: IBAN CH9300762011623852957, password Secret123! (force GLINER2 to load).");
            log.info("[bench] GLINER2 warmup OK: {} findings", warm.sensitiveDataFound().size());
        } catch (RuntimeException e) {
            log.warn("[bench] GLINER2 warmup failed (continuing): {}", e.toString());
        }
    }

    private void estimateGliner2(List<GoldDoc> gold) {
        GoldDoc probe = gold.stream().min(Comparator.comparingInt(d -> d.text().length())).orElseThrow();
        Instant t = Instant.now();
        ContentPiiDetection r = analyzeWithRetry(probe.id(), probe.text());
        long probeMs = Duration.between(t, Instant.now()).toMillis();
        if (r == null) {
            log.warn("[bench] GLINER2 estimate probe failed on shortest doc {} — skipping ETA", probe.id());
            return;
        }
        long etaS = Math.round(probeMs / 1000.0 * gold.size());
        log.info("[bench] >>> GLINER2 ESTIMATED RUN TIME: shortest doc {} ({} chars) took {}ms -> ~{} for {} docs <<<",
            probe.id(), probe.text().length(), probeMs, humanDuration(etaS), gold.size());
    }

    /** 3 attempts with backoff; null when all fail. Mirrors the other detector ITs. */
    private ContentPiiDetection analyzeWithRetry(String docId, String text) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return piiDetectorClient.analyzeContent(text);
            } catch (RuntimeException t) {
                String msg = t.toString();
                if (msg.contains("INVALID_ARGUMENT") || msg.contains("Content too large") || attempt == maxAttempts) {
                    log.warn("[bench] GLINER2 analyze failed for {}: {}", docId, msg);
                    return null;
                }
                LockSupport.parkNanos(5_000L * attempt * 1_000_000L);
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    // ---- ministral evaluation (endpoint) -------------------------------------

    private ExtractorReport.ModelEval evaluateMinistral(List<GoldDoc> gold, Path extractorMapPath)
            throws IOException {
        ExtractorModel model = ministralModel();
        LlmExtractorClient client = newLlmClient();
        ExtractorConceptMap extractorMap = ExtractorConceptMap.load(extractorMapPath);
        Duration requestTimeout = Duration.ofSeconds(intProp("bench.extractor.request-timeout-s", 120));

        log.info("[bench] === ministral {} ({}) over {} docs ===", model.name(), model.model(), gold.size());
        probeAndWarmupMinistral(client, model, requestTimeout);
        estimateMinistral(client, model, gold, requestTimeout);

        Map<String, List<Prediction>> predsByDoc = new LinkedHashMap<>();
        int rawEntities = 0;
        int dropped = 0;
        int parseFailures = 0;
        int httpFailures = 0;
        Instant loopStart = Instant.now();
        int idx = 0;
        for (GoldDoc doc : gold) {
            idx++;
            Instant tDoc = Instant.now();
            ExtractResult result = extractWithRetry(client, model, doc.text(), doc.id(), requestTimeout);
            if (result == null) {
                httpFailures++;
                log.warn("[bench] ministral [{}/{}] {} : HTTP FAIL ({}s) | ETA ~{}", idx, gold.size(), doc.id(),
                    Duration.between(tDoc, Instant.now()).toSeconds(), etaFor(loopStart, idx, gold.size()));
                continue;
            }
            if (!result.jsonArrayFound()) {
                parseFailures++;
            }
            rawEntities += result.entities().size();
            List<Prediction> preds = ministralPredictions(result.entities(), extractorMap, model.name());
            dropped += result.entities().size() - preds.size();
            predsByDoc.put(doc.id(), preds);
            log.info("[bench] ministral [{}/{}] {} : {} raw -> {} in-scope ({}s){} | ETA ~{}", idx, gold.size(),
                doc.id(), result.entities().size(), preds.size(),
                Duration.between(tDoc, Instant.now()).toSeconds(),
                result.jsonArrayFound() ? "" : " [NO JSON ARRAY]", etaFor(loopStart, idx, gold.size()));
        }

        ScoreResult score = ValueScorer.score(gold, predsByDoc);
        log.info("[bench] ministral : F1={} P={} R={} raw={} dropped={} parseFail={} httpFail={} unknown={}",
            score.strictOverall().f1(), score.strictOverall().precision(), score.strictOverall().recall(),
            rawEntities, dropped, parseFailures, httpFailures, extractorMap.unknownLabels());
        return new ExtractorReport.ModelEval(model.name(), model.model(), score, gold.size(), rawEntities,
            dropped, parseFailures, httpFailures, extractorMap.unknownLabels());
    }

    /** Projects LLM entities onto (canonical, value); drops out-of-scope (IGNORE/null) labels. */
    private static List<Prediction> ministralPredictions(List<RawEntity> entities, ExtractorConceptMap extractorMap,
                                                          String modelName) {
        List<Prediction> preds = new ArrayList<>();
        for (RawEntity raw : entities) {
            String canonical = extractorMap.canonical(modelName, raw.label());
            if (canonical != null) {
                preds.add(new Prediction(canonical, raw.value()));
            }
        }
        return preds;
    }

    /**
     * Lists the served models (fast preflight) and warms the model up, failing the
     * test with an actionable message if it never responds — a missing model or a
     * dead endpoint is the most common reason the comparison cannot run.
     */
    private void probeAndWarmupMinistral(LlmExtractorClient client, ExtractorModel model, Duration requestTimeout) {
        try {
            Set<String> served = client.listModelIds(model.baseUrl(), PREFLIGHT_TIMEOUT);
            log.info("[bench] endpoint {} reachable, serves {} model(s): {}", model.baseUrl(), served.size(), served);
            if (!served.isEmpty() && !served.contains(model.model())) {
                log.warn("[bench] model id '{}' NOT in the served list {} — check the @quant suffix; "
                    + "the id must match exactly or the endpoint will time out", model.model(), served);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.warn("[bench] endpoint {} preflight FAILED: {}", model.baseUrl(), e.toString());
        }

        Duration warmupTimeout = requestTimeout.compareTo(MIN_WARMUP_TIMEOUT) < 0 ? MIN_WARMUP_TIMEOUT : requestTimeout;
        Instant t0 = Instant.now();
        log.info("[bench] ministral warming up (loading model, can take a while on first call)...");
        boolean ready;
        try {
            ExtractResult r = client.extract(model, "Warmup: email a@b.io, IBAN CH9300762011623852957.", warmupTimeout);
            ready = true;
            log.info("[bench] ministral warmup OK in {}s ({} entities, jsonArray={})",
                Duration.between(t0, Instant.now()).toSeconds(), r.entities().size(), r.jsonArrayFound());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ready = false;
        } catch (IOException e) {
            log.warn("[bench] ministral warmup failed in {}s: {}",
                Duration.between(t0, Instant.now()).toSeconds(), e.toString());
            ready = false;
        }
        assertThat(ready)
            .as("ministral model '%s' at %s is not ready (warmup failed) — load it in LM Studio, check the "
                + "@quant suffix, and ensure the endpoint is reachable from the host JVM (use localhost or the "
                + "LAN IP, NOT host.docker.internal). Raise -Dbench.extractor.request-timeout-s if it is just slow.",
                model.model(), model.baseUrl())
            .isTrue();
    }

    private void estimateMinistral(LlmExtractorClient client, ExtractorModel model, List<GoldDoc> gold,
                                   Duration requestTimeout) {
        GoldDoc probe = gold.stream().min(Comparator.comparingInt(d -> d.text().length())).orElseThrow();
        Instant t = Instant.now();
        ExtractResult r = extractWithRetry(client, model, probe.text(), probe.id(), requestTimeout);
        long probeMs = Duration.between(t, Instant.now()).toMillis();
        if (r == null) {
            log.warn("[bench] ministral estimate probe failed on shortest doc {} — skipping ETA", probe.id());
            return;
        }
        long etaS = Math.round(probeMs / 1000.0 * gold.size());
        log.info("[bench] >>> ministral ESTIMATED RUN TIME: shortest doc {} ({} chars) took {}ms -> ~{} for {} docs "
            + "(lower bound — longer docs push this up) <<<",
            probe.id(), probe.text().length(), probeMs, humanDuration(etaS), gold.size());
    }

    /** 2 attempts; null when both fail (counts as an HTTP failure for the doc). */
    private ExtractResult extractWithRetry(LlmExtractorClient client, ExtractorModel model, String text,
                                           String label, Duration requestTimeout) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return client.extract(model, text, requestTimeout);
            } catch (IOException e) {
                if (attempt == 2) {
                    log.warn("[bench] ministral {} failed: {}", label, e.toString());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private LlmExtractorClient newLlmClient() {
        int maxTokens = intProp("bench.extractor.max-tokens", 2048);
        boolean jsonSchema = Boolean.parseBoolean(System.getProperty("bench.extractor.json-schema", "true"));
        return new LlmExtractorClient(Duration.ofSeconds(30), maxTokens, jsonSchema);
    }

    private ExtractorModel ministralModel() {
        return new ExtractorModel(MINISTRAL_NAME, MINISTRAL_BASE_URL, MINISTRAL_MODEL, MINISTRAL_SYSTEM_PROMPT);
    }

    // ---- shared helpers ------------------------------------------------------

    private List<GoldDoc> loadGold(Path goldDir) throws IOException {
        if (!Files.isDirectory(goldDir)) {
            return List.of();
        }
        List<GoldDoc> all = GoldDataset.loadDir(goldDir);
        Integer maxDocs = parseIntProperty("corpus.bench.max-docs");
        if (maxDocs != null && maxDocs > 0 && all.size() > maxDocs) {
            return all.subList(0, maxDocs);
        }
        return all;
    }

    private static int predictionCount(ExtractorReport.ModelEval eval) {
        return eval.score().strictOverall().tp() + eval.score().strictOverall().fp();
    }

    private void resetAndReseedDb(String sqlClasspath) throws SQLException {
        jdbcTemplate.execute("DELETE FROM pii_type_config");
        jdbcTemplate.execute("DELETE FROM pii_detection_config");
        Resource resource = new DefaultResourceLoader().getResource(sqlClasspath);
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new EncodedResource(resource, StandardCharsets.UTF_8));
        }
    }

    private static Path goldDir() {
        return Paths.get(System.getProperty(
            "corpus.bench.gold-dir", "../benchmarks/pii-dataset-eval/gold")).toAbsolutePath();
    }

    private static Path conceptMapPath() {
        return Paths.get(System.getProperty(
            "corpus.bench.concept-map",
            "../benchmarks/pii-dataset-eval/mappings/detector_concept_map.json")).toAbsolutePath();
    }

    private static Path extractorMapPath() {
        return Paths.get(System.getProperty(
            "bench.extractor-concept-map",
            "../benchmarks/pii-dataset-eval/mappings/extractor_concept_map.json")).toAbsolutePath();
    }

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
        if (m < 60) {
            return s == 0 ? m + "m" : m + "m " + s + "s";
        }
        long h = m / 60;
        m = m % 60;
        return String.format(Locale.ROOT, "%dh%02dm", h, m);
    }

    private static int intProp(String key, int def) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? def : Integer.parseInt(v);
    }

    private static Double parseDoubleProperty(String key) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? null : Double.parseDouble(v);
    }

    private static Integer parseIntProperty(String key) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? null : Integer.parseInt(v);
    }

    // ---- container infra (mirrors PiiDetectorBenchmarkIT) --------------------

    private static String ensureHfCacheDir() {
        try {
            Files.createDirectories(HF_CACHE_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create HF cache dir: " + HF_CACHE_DIR, e);
        }
        return HF_CACHE_DIR.toString();
    }

    private static ImageFromDockerfile buildPiiDetectorImage() {
        Path repoRoot = Paths.get("..").toAbsolutePath().normalize();
        Path detectorRoot = repoRoot.resolve("pii-detector-service");
        // Same image name as PiiDetectorBenchmarkIT so the built image is shared/cached.
        return new ImageFromDockerfile("ai-sentinel-pii-detector-it", false)
            .withFileFromPath("pii-detector-service/Dockerfile", detectorRoot.resolve("Dockerfile"))
            .withFileFromPath("pii-detector-service/pyproject.toml", detectorRoot.resolve("pyproject.toml"))
            .withFileFromPath("pii-detector-service/README.md", detectorRoot.resolve("README.md"))
            .withFileFromPath("pii-detector-service/pii_detector", detectorRoot.resolve("pii_detector"))
            .withFileFromPath("pii-detector-service/config", detectorRoot.resolve("config"))
            .withFileFromPath("pii-detector-service/docker-entrypoint.sh", detectorRoot.resolve("docker-entrypoint.sh"))
            .withFileFromPath("proto", repoRoot.resolve("proto"))
            // The Dockerfile's COPY paths are repo-root-relative; withDockerfilePath points at the
            // Dockerfile *inside* the assembled context above, keeping that repo-root-shaped context
            // (see PiiDetectorBenchmarkIT for the full rationale).
            .withDockerfilePath("pii-detector-service/Dockerfile");
    }

    private static void routeContainerLog(OutputFrame frame) {
        String raw = frame.getUtf8String();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String line = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        if (line.contains(" - DEBUG - ")) {
            CONTAINER_LOG.debug(line);
        } else if (line.contains(" - WARNING - ") || line.contains(" - WARN - ")) {
            CONTAINER_LOG.warn(line);
        } else if (line.contains(" - ERROR - ") || line.contains(" - CRITICAL - ")) {
            CONTAINER_LOG.error(line);
        } else {
            CONTAINER_LOG.info(line);
        }
    }
}
