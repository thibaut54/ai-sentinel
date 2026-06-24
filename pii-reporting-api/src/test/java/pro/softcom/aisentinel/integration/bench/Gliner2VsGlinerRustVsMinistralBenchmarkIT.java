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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
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
import org.testcontainers.containers.BindMode;
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
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.SensitiveData;
import pro.softcom.aisentinel.integration.bench.LlmExtractorClient.ExtractResult;
import pro.softcom.aisentinel.integration.bench.LlmExtractorClient.RawEntity;

/**
 * Three-way arbitration benchmark answering: <em>which single PII model should we
 * keep — the Python GLINER2 <b>privacy-filter</b> (isolated), the
 * <b>gliner-large</b> NER model served by the standalone Rust detector
 * ({@code pii-detector-rust}), or the generative
 * {@code ministral-3b-pii-preview@q8_0} extractor?</em> Each arm isolates exactly
 * <em>one</em> model; all three are scored on the same labelled gold with the same
 * value-level metric ({@link ValueScorer}: canonical concept + normalised value),
 * so their precision/recall/F1 are directly comparable and {@link ExtractorReport}
 * writes the side-by-side table plus the pairwise Δ.
 *
 * <h2>Each arm isolates a SINGLE model (no LLM-as-judge anywhere)</h2>
 * <ul>
 *   <li><b>GLINER2 (privacy-filter)</b> runs through the same containerised Java +
 *       gRPC stack as production (Postgres + the Python {@code pii-detector}) but
 *       with <b>only GLINER2 enabled</b> — PRESIDIO/REGEX/OPENMED off, every
 *       {@code *_judge_enabled} and {@code llm_judge_enabled} off,
 *       {@code prefilter_enabled} off ({@link #seedPythonIsolatedGliner2NoJudge}).
 *       Detections carry {@code source = GLINER2} and map through
 *       {@link ConceptMap}.</li>
 *   <li><b>gliner-large (Rust)</b> runs the standalone Rust gRPC service with
 *       <b>only its NER (gliner-large) layer active</b>: the container is started
 *       with {@code --override-path .../overrides/ner-only.toml}, which disables
 *       every regex-layer IPI (the classification tier is already empty), so only
 *       the gliner-large model produces findings. Its pipeline is entirely local —
 *       no DB, no LLM judge. The Rust proto omits the source field, so each
 *       finding's {@code type} is the taxonomy IPI id; it is projected to canonical
 *       concepts by type alone via {@link ExtractorConceptMap} (the
 *       {@code gliner-rust} map), symmetric to the LLM extractor. <b>Note:</b>
 *       gliner-large then only covers its configured NER labels (username,
 *       national id, account id, medical record / health insurance number,
 *       password); regex-typed PII (IBAN, phone, card, IP, AVS) is not in its
 *       scope — that is what "uniquement gliner-large actif" means.</li>
 *   <li><b>ministral</b> is called over an OpenAI-compatible endpoint (LM Studio)
 *       from the host JVM ({@link LlmExtractorClient}); its labels map via
 *       {@link ExtractorConceptMap}, dropping out-of-scope concepts to
 *       {@code IGNORE}.</li>
 * </ul>
 * Out-of-scope PII (email, names, dates) is dropped on every side, so no model is
 * rewarded nor penalised for it.
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Gold built: {@code python benchmarks/pii-dataset-eval/build_datasets.py}.</li>
 *   <li>Docker (Python + Rust detector containers).</li>
 *   <li>The Rust GLiNER2 ONNX model (~3.7 GB) on disk, pointed at by
 *       {@code -Drust.bench.model-dir} (default
 *       {@code ~/.ai-sentinel-it-hf-cache/gliner2-large-v1-onnx}). Download once:
 *       <pre>hf download lmo3/gliner2-large-v1-onnx --local-dir &lt;dir&gt; \
 *   --include "gliner2_config.json" "tokenizer.json" "tokenizer_config.json" \
 *             "onnx/encoder.onnx" "onnx/encoder.onnx.data" \
 *             "onnx/span_rep.onnx" "onnx/span_rep.onnx.data" \
 *             "onnx/classifier.onnx" "onnx/classifier.onnx.data" \
 *             "onnx/count_embed.onnx" "onnx/count_embed.onnx.data"</pre>
 *       When the model dir is absent the Rust smoke {@link Assumptions skips}
 *       cleanly and the full comparison runs GLINER2-vs-ministral only.</li>
 *   <li>A reachable OpenAI-compatible endpoint for ministral
 *       ({@code http://localhost:1234/v1} by default).</li>
 * </ol>
 *
 * <h2>Run</h2>
 * Env gate {@code RUN_GLINER2_RUST_3WAY_BENCHMARK=true}. The three {@code smoke*}
 * methods validate each side on one short text in seconds; each is independent and
 * skippable, so the runnable sides still prove out when a prerequisite is missing.
 * <pre>
 *   $env:RUN_GLINER2_RUST_3WAY_BENCHMARK = "true"
 *   mvn -Dtest=Gliner2VsGlinerRustVsMinistralBenchmarkIT `
 *       "-Drust.bench.model-dir=C:\path\to\gliner2-large-v1-onnx" `
 *       "-Dbench.ministral.base-url=http://localhost:1234/v1" test
 * </pre>
 * Useful system properties: {@code corpus.bench.gold-dir}, {@code corpus.bench.concept-map},
 * {@code bench.extractor-concept-map} (carries both the ministral and {@code gliner-rust} maps),
 * {@code corpus.bench.max-docs}, {@code corpus.bench.threshold}, {@code rust.bench.model-dir},
 * {@code bench.rust.request-timeout-s} (120), {@code bench.ministral.base-url},
 * {@code bench.ministral.model}.
 */
@Testcontainers
@SpringBootTest(classes = AiSentinelApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "RUN_GLINER2_RUST_3WAY_BENCHMARK", matches = "true")
class Gliner2VsGlinerRustVsMinistralBenchmarkIT {

    private static final Logger log = LoggerFactory.getLogger(Gliner2VsGlinerRustVsMinistralBenchmarkIT.class);
    private static final Logger PY_CONTAINER_LOG = LoggerFactory.getLogger("pii-detector-container");
    private static final Logger RUST_CONTAINER_LOG = LoggerFactory.getLogger("pii-detector-rust-container");

    private static final int GRPC_PORT = 50051;
    private static final String POSTGRES_ALIAS = "postgres-it";
    private static final String DB_NAME = "ai-sentinel";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";

    private static final String MASTER_SEED = "classpath:sql/data-improved-gliner2-presidio-regex.sql";
    private static final String OUTPUT_ROOT = "target/pii-bench/gliner2-vs-rust-vs-ministral";
    private static final String RUST_MODEL_MOUNT = "/app/models/gliner2-large-v1-onnx";

    /** Short reachability preflight for the LLM endpoint's GET /models. */
    private static final Duration PREFLIGHT_TIMEOUT = Duration.ofSeconds(10);
    /** First call to a cold LM Studio JIT-loads the model; absorb it once. */
    private static final Duration MIN_WARMUP_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Short synthetic text mixing PII types each isolated model can catch. It
     * includes regex-typed PII (IBAN, AVS, card, IP) for the privacy-filter and
     * ministral arms <em>and</em> NER-typed PII (username, account id, medical
     * record number, password) so the gliner-large NER-only arm also has in-scope
     * targets — without paying the cost of the whole gold set.
     */
    private static final String SMOKE_TEXT = String.join("\n",
        "Coordonnees bancaires : IBAN CH9300762011623852957.",
        "Numero AVS : 756.3047.5009.62.",
        "Carte de credit : 4111 1111 1111 1111, CVV 123.",
        "Adresse IP du serveur : 192.168.1.42.",
        "Nom d'utilisateur : jdupont88 ; mot de passe : MotDePasseSecret123!",
        "Identifiant de compte client : ACCT-99812.",
        "Numero de dossier medical du patient : MRN-4471.");

    private static final Path HF_CACHE_DIR = Paths.get(
        System.getProperty("corpus.bench.hf-cache",
            System.getProperty("user.home") + "/.ai-sentinel-it-hf-cache"));

    // ministral extractor (host JVM -> endpoint; localhost/LAN, NOT host.docker.internal).
    private static final String MINISTRAL_NAME = System.getProperty("bench.ministral.name", "ministral-3b-pii");
    private static final String MINISTRAL_BASE_URL =
        System.getProperty("bench.ministral.base-url", "http://localhost:1234/v1");
    private static final String MINISTRAL_MODEL =
        System.getProperty("bench.ministral.model", "ministral-3b-pii-preview@q8_0");
    private static final String MINISTRAL_SYSTEM_PROMPT = System.getProperty("bench.ministral.system-prompt", null);

    /** Display name used for the Rust model in {@link ExtractorConceptMap} + the report. */
    private static final String RUST_MODEL_NAME = "gliner-rust";

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
        // (seedPythonFullPipelineNoJudge), so the composite is measured judge-free.
        .withLogConsumer(Gliner2VsGlinerRustVsMinistralBenchmarkIT::routePythonLog)
        .waitingFor(Wait.forLogMessage(".*Server started on port.*", 1))
        .withStartupTimeout(Duration.ofMinutes(10));

    /**
     * The Rust detector container is started lazily (not a {@code @Container}) so
     * the suite degrades to a clean skip when the ONNX model dir is absent instead
     * of failing the whole class on container startup.
     */
    private static GenericContainer<?> rustDetector;
    private static RustPiiDetectorClient rustClient;

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

    @AfterAll
    static void stopRust() {
        if (rustClient != null) {
            rustClient.close();
        }
        if (rustDetector != null && rustDetector.isRunning()) {
            rustDetector.stop();
        }
    }

    // ---- smoke tests (one per side, fast, independent) -----------------------

    /**
     * Smoke (Order 0): the isolated Python GLINER2 (privacy-filter, judge off)
     * produces at least one in-scope prediction on a short text. Validates the
     * image build, Postgres reseed and gRPC wiring with no external dependency
     * beyond Docker.
     */
    @Test
    @Order(0)
    void smokeGliner2IsolatedProducesInScopePredictions() throws Exception {
        log.info("[smoke gliner2] === START ===");
        seedPythonIsolatedGliner2NoJudge(null);
        ConceptMap detectorMap = ConceptMap.load(conceptMapPath());
        Instant t0 = Instant.now();
        ContentPiiDetection detection = piiDetectorClient.analyzeContent(SMOKE_TEXT);
        assertThat(detection).as("GLINER2 analyzeContent returned null").isNotNull();
        List<Prediction> preds = pythonPredictions(detection, detectorMap);
        log.info("[smoke gliner2] -> {} raw, {} in-scope predictions in {}s",
            detection.sensitiveDataFound().size(), preds.size(),
            Duration.between(t0, Instant.now()).toSeconds());
        assertThat(preds)
            .as("GLINER2 (privacy-filter, isolated) produced 0 in-scope predictions on the smoke text — the "
                + "image build, the model load, the gRPC wiring or the seed is broken")
            .isNotEmpty();
        log.info("[smoke gliner2] === DONE ===");
    }

    /**
     * Smoke (Order 1): the standalone Rust detector produces at least one in-scope
     * prediction. Skipped (not failed) when the ONNX model dir is absent.
     */
    @Test
    @Order(1)
    void smokeGlinerRustProducesInScopePredictions() {
        Assumptions.assumeTrue(rustModelPresent(),
            "Rust model dir absent (" + rustModelDir() + ") — download lmo3/gliner2-large-v1-onnx "
                + "(see class javadoc) or set -Drust.bench.model-dir. Skipping Rust smoke.");
        log.info("[smoke rust] === START ===");
        RustPiiDetectorClient client = ensureRustStarted();
        assertThat(client).as("Rust container failed to start").isNotNull();
        ExtractorConceptMap rustMap = loadExtractorMap();
        Instant t0 = Instant.now();
        List<RawEntity> raw = client.detect(SMOKE_TEXT, rustTimeout().toMillis());
        List<Prediction> preds = rustPredictions(raw, rustMap);
        log.info("[smoke rust] -> {} raw, {} in-scope predictions in {}s (unknown labels so far: {})",
            raw.size(), preds.size(), Duration.between(t0, Instant.now()).toSeconds(), rustMap.unknownLabels());
        assertThat(preds)
            .as("Rust pipeline produced 0 in-scope predictions on the smoke text — check the model mount "
                + "at %s, the image build, or the [gliner-rust] concept map", RUST_MODEL_MOUNT)
            .isNotEmpty();
        log.info("[smoke rust] === DONE ===");
    }

    /**
     * Smoke (Order 2): the ministral extractor produces at least one in-scope
     * prediction. Skipped (not failed) when the LLM endpoint is unreachable.
     */
    @Test
    @Order(2)
    void smokeMinistralProducesInScopePredictions() {
        LlmExtractorClient client = newLlmClient();
        ExtractorModel model = ministralModel();
        Assumptions.assumeTrue(endpointReachable(client, model),
            "ministral endpoint " + model.baseUrl() + " unreachable — start LM Studio. Skipping ministral smoke.");
        log.info("[smoke ministral] === START ===");
        ExtractorConceptMap extractorMap = loadExtractorMap();
        Duration requestTimeout = Duration.ofSeconds(intProp("bench.extractor.request-timeout-s", 120));
        probeAndWarmupMinistral(client, model, requestTimeout);
        ExtractResult result = extractWithRetry(client, model, SMOKE_TEXT, "smoke", requestTimeout);
        assertThat(result).as("ministral endpoint %s unreachable on the smoke text", model.baseUrl()).isNotNull();
        List<Prediction> preds = ministralPredictions(result.entities(), extractorMap, model.name());
        log.info("[smoke ministral] -> {} raw, {} in-scope predictions (jsonArray={})",
            result.entities().size(), preds.size(), result.jsonArrayFound());
        assertThat(preds)
            .as("ministral produced 0 in-scope predictions on the smoke text — check the model id "
                + "'%s' (@quant suffix) and the [extractors] label map", model.model())
            .isNotEmpty();
        log.info("[smoke ministral] === DONE ===");
    }

    // ---- full three-way comparison -------------------------------------------

    /**
     * Full comparison: score the GLINER2 full pipeline, the Rust detector and
     * ministral over the gold set with the same value-level metric, write the
     * side-by-side report with pairwise Δ, and assert every available side
     * produced in-scope predictions. The Rust side is included when its model dir
     * is present; otherwise it is skipped with a warning (the report still
     * compares GLINER2 vs ministral).
     */
    @Test
    @Order(3)
    void compareGliner2VsGlinerRustVsMinistral() throws Exception {
        List<GoldDoc> gold = loadGold(goldDir());
        assertThat(gold)
            .as("gold dataset must be built first (see benchmarks/pii-dataset-eval/README.md)")
            .isNotEmpty();
        Double threshold = parseDoubleProperty("corpus.bench.threshold");
        log.info("[bench] {} gold docs, threshold={}, output={}",
            gold.size(), threshold == null ? "seed defaults" : threshold, Paths.get(OUTPUT_ROOT).toAbsolutePath());

        Instant start = Instant.now();
        ExtractorReport.ModelEval gliner2Eval = evaluateGliner2Isolated(gold, ConceptMap.load(conceptMapPath()),
            threshold);

        ExtractorReport.ModelEval rustEval = null;
        if (rustModelPresent()) {
            rustEval = evaluateRust(gold);
        } else {
            log.warn("[bench] Rust model dir absent ({}) — SKIPPING the Rust side; the report compares "
                + "GLINER2 vs ministral only. Download lmo3/gliner2-large-v1-onnx to include it.", rustModelDir());
        }

        ExtractorReport.ModelEval ministralEval = evaluateMinistral(gold);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("comparison", "isolated models, value-level: GLINER2 privacy-filter vs gliner-large (Rust NER) vs LLM");
        meta.put("question", "which single model to keep: GLINER2 privacy-filter, gliner-large (Rust), or "
            + MINISTRAL_MODEL + "?");
        meta.put("gold_docs", gold.size());
        meta.put("metric", "value-level (canonical concept + normalised value)");
        meta.put("gliner2_scope", "ISOLATED: only GLINER2 (privacy-filter) enabled, no judge, no prefilter");
        meta.put("rust_scope", rustEval == null ? "SKIPPED (model dir absent)"
            : "ISOLATED: only the gliner-large NER layer (regex+classification disabled), no judge");
        meta.put("rust_model_dir", rustModelDir());
        meta.put("ministral_endpoint", MINISTRAL_BASE_URL);
        meta.put("ministral_model", MINISTRAL_MODEL);
        meta.put("threshold_override", threshold == null ? "seed defaults" : threshold);
        meta.put("duration_s", Duration.between(start, Instant.now()).toSeconds());

        Path outDir = Paths.get(OUTPUT_ROOT).toAbsolutePath();
        // Order [GLINER2, Rust, ministral] so the pairwise Δ reads "later − earlier".
        List<ExtractorReport.ModelEval> evals = new ArrayList<>();
        evals.add(gliner2Eval);
        if (rustEval != null) {
            evals.add(rustEval);
        }
        evals.add(ministralEval);
        ExtractorReport.write(outDir, evals, meta);
        log.info("[bench] === report written to {} ===", outDir);
        assertThat(outDir.resolve("extractor-comparison.md")).exists();

        assertThat(predictionCount(gliner2Eval))
            .as("GLINER2 (privacy-filter, isolated) produced no in-scope prediction across the gold set — "
                + "check the container/seed")
            .isPositive();
        if (rustEval != null) {
            // gliner-large NER-only has a sparse scope (only its NER labels), so a
            // small -Dcorpus.bench.max-docs subset can legitimately contain none of
            // them. Fail loudly only on the FULL gold (where the gold has plenty of
            // username/national-id/account-id); otherwise just warn.
            if (parseIntProperty("corpus.bench.max-docs") == null) {
                assertThat(predictionCount(rustEval))
                    .as("Rust (gliner-large NER-only) produced no in-scope prediction across the FULL gold — "
                        + "check the container/model/override/concept map")
                    .isPositive();
            } else if (predictionCount(rustEval) == 0) {
                log.warn("[bench] Rust (gliner-large NER-only) produced 0 in-scope predictions on this max-docs "
                    + "subset — its NER scope is sparse; run the full gold for a meaningful score.");
            }
        }
        assertThat(predictionCount(ministralEval))
            .as("ministral produced no in-scope prediction across the gold set — check the endpoint and model id '%s'",
                MINISTRAL_MODEL)
            .isPositive();
    }

    // ---- GLINER2 full-pipeline evaluation (Python container) -----------------

    private ExtractorReport.ModelEval evaluateGliner2Isolated(List<GoldDoc> gold, ConceptMap detectorMap,
            Double threshold) throws SQLException {
        log.info("[bench] === GLINER2 isolated (privacy-filter only, judge OFF) over {} docs ===", gold.size());
        seedPythonIsolatedGliner2NoJudge(threshold);
        warmupGliner2();
        estimatePython(gold);

        Map<String, List<Prediction>> predsByDoc = new LinkedHashMap<>();
        int rawEntities = 0;
        int httpFailures = 0;
        Instant loopStart = Instant.now();
        int idx = 0;
        for (GoldDoc doc : gold) {
            idx++;
            Instant tDoc = Instant.now();
            ContentPiiDetection detection = analyzePythonWithRetry(doc.id(), doc.text());
            if (detection == null) {
                httpFailures++;
                log.warn("[bench] GLINER2 [{}/{}] {} : FAILED ({}s) | ETA ~{}", idx, gold.size(), doc.id(),
                    Duration.between(tDoc, Instant.now()).toSeconds(), etaFor(loopStart, idx, gold.size()));
                continue;
            }
            rawEntities += detection.sensitiveDataFound().size();
            List<Prediction> preds = pythonPredictions(detection, detectorMap);
            predsByDoc.put(doc.id(), preds);
            log.info("[bench] GLINER2 [{}/{}] {} : {} raw -> {} in-scope ({}s) | ETA ~{}", idx, gold.size(),
                doc.id(), detection.sensitiveDataFound().size(), preds.size(),
                Duration.between(tDoc, Instant.now()).toSeconds(), etaFor(loopStart, idx, gold.size()));
        }

        ScoreResult score = ValueScorer.score(gold, predsByDoc);
        log.info("[bench] GLINER2 : F1={} P={} R={} raw={} httpFail={}", score.strictOverall().f1(),
            score.strictOverall().precision(), score.strictOverall().recall(), rawEntities, httpFailures);
        return new ExtractorReport.ModelEval("GLINER2-privacy-filter",
            "gliner2 privacy-filter only (isolated, no judge)", score,
            gold.size(), rawEntities, 0, 0, httpFailures, Set.of());
    }

    /** Projects detections onto (canonical, value) via the source-aware {@link ConceptMap}. */
    private static List<Prediction> pythonPredictions(ContentPiiDetection detection, ConceptMap detectorMap) {
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
     * Reseeds the master seed and isolates GLINER2 (privacy-filter): only
     * {@code gliner2_enabled} is true, every other detector and every judge is OFF
     * and the prefilter is OFF, so {@code sensitiveDataFound()} is the raw
     * privacy-filter output with no LLM-as-judge post-filter — the apples-to-apples
     * counterpart of the Rust gliner-large NER-only arm.
     */
    private void seedPythonIsolatedGliner2NoJudge(Double threshold) throws SQLException {
        resetAndReseedDb(MASTER_SEED);
        StringBuilder sql = new StringBuilder("UPDATE pii_detection_config SET "
            + "gliner2_enabled = true, presidio_enabled = false, regex_enabled = false, "
            + "gliner_enabled = false, openmed_enabled = false, "
            + "llm_judge_enabled = false, gliner_judge_enabled = false, presidio_judge_enabled = false, "
            + "regex_judge_enabled = false, openmed_judge_enabled = false, gliner2_judge_enabled = false, "
            + "prefilter_enabled = false");
        if (threshold != null) {
            sql.append(String.format(Locale.ROOT, ", default_threshold = %.2f", threshold));
        }
        sql.append(" WHERE id = 1");
        jdbcTemplate.execute(sql.toString());
        if (threshold != null) {
            jdbcTemplate.update("UPDATE pii_type_config SET threshold = ? WHERE detector = 'GLINER2'", threshold);
        }
    }

    private void warmupGliner2() {
        try {
            ContentPiiDetection warm = piiDetectorClient.analyzeContent(
                "Warmup: IBAN CH9300762011623852957, password Secret123! (force the models to load).");
            log.info("[bench] GLINER2 warmup OK: {} findings", warm.sensitiveDataFound().size());
        } catch (RuntimeException e) {
            log.warn("[bench] GLINER2 warmup failed (continuing): {}", e.toString());
        }
    }

    private void estimatePython(List<GoldDoc> gold) {
        GoldDoc probe = gold.stream().min(Comparator.comparingInt(d -> d.text().length())).orElseThrow();
        Instant t = Instant.now();
        ContentPiiDetection r = analyzePythonWithRetry(probe.id(), probe.text());
        long probeMs = Duration.between(t, Instant.now()).toMillis();
        if (r == null) {
            log.warn("[bench] GLINER2 estimate probe failed on shortest doc {} — skipping ETA", probe.id());
            return;
        }
        long etaS = Math.round(probeMs / 1000.0 * gold.size());
        log.info("[bench] >>> GLINER2 ESTIMATED RUN TIME: shortest doc {} ({} chars) took {}ms -> ~{} for {} docs <<<",
            probe.id(), probe.text().length(), probeMs, humanDuration(etaS), gold.size());
    }

    /** 3 attempts with backoff; null when all fail. */
    private ContentPiiDetection analyzePythonWithRetry(String docId, String text) {
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

    // ---- Rust evaluation (standalone container) ------------------------------

    private ExtractorReport.ModelEval evaluateRust(List<GoldDoc> gold) {
        log.info("[bench] === gliner-large (Rust NER-only, regex disabled, no judge) over {} docs ===", gold.size());
        RustPiiDetectorClient client = ensureRustStarted();
        ExtractorConceptMap rustMap = loadExtractorMap();
        long timeoutMs = rustTimeout().toMillis();
        warmupRust(client, timeoutMs);
        estimateRust(client, gold, timeoutMs);

        Map<String, List<Prediction>> predsByDoc = new LinkedHashMap<>();
        int rawEntities = 0;
        int dropped = 0;
        int httpFailures = 0;
        Instant loopStart = Instant.now();
        int idx = 0;
        for (GoldDoc doc : gold) {
            idx++;
            Instant tDoc = Instant.now();
            List<RawEntity> raw = detectRustWithRetry(client, doc.id(), doc.text(), timeoutMs);
            if (raw == null) {
                httpFailures++;
                log.warn("[bench] Rust [{}/{}] {} : FAILED ({}s) | ETA ~{}", idx, gold.size(), doc.id(),
                    Duration.between(tDoc, Instant.now()).toSeconds(), etaFor(loopStart, idx, gold.size()));
                continue;
            }
            rawEntities += raw.size();
            List<Prediction> preds = rustPredictions(raw, rustMap);
            dropped += raw.size() - preds.size();
            predsByDoc.put(doc.id(), preds);
            log.info("[bench] Rust [{}/{}] {} : {} raw -> {} in-scope ({}s) | ETA ~{}", idx, gold.size(),
                doc.id(), raw.size(), preds.size(),
                Duration.between(tDoc, Instant.now()).toSeconds(), etaFor(loopStart, idx, gold.size()));
        }

        ScoreResult score = ValueScorer.score(gold, predsByDoc);
        log.info("[bench] Rust : F1={} P={} R={} raw={} dropped={} httpFail={} unknown={}",
            score.strictOverall().f1(), score.strictOverall().precision(), score.strictOverall().recall(),
            rawEntities, dropped, httpFailures, rustMap.unknownLabels());
        return new ExtractorReport.ModelEval("gliner-large",
            "pii-detector-rust gliner-large NER only (regex layer disabled, no judge)", score,
            gold.size(), rawEntities, dropped, 0, httpFailures, rustMap.unknownLabels());
    }

    /** Projects Rust entities onto (canonical, value); drops out-of-scope (IGNORE/unknown) labels. */
    private static List<Prediction> rustPredictions(List<RawEntity> entities, ExtractorConceptMap rustMap) {
        List<Prediction> preds = new ArrayList<>();
        for (RawEntity raw : entities) {
            String canonical = rustMap.canonical(RUST_MODEL_NAME, raw.label());
            if (canonical != null) {
                preds.add(new Prediction(canonical, raw.value()));
            }
        }
        return preds;
    }

    private void warmupRust(RustPiiDetectorClient client, long timeoutMs) {
        try {
            List<RawEntity> warm = client.detect(
                "Warmup: IBAN CH9300762011623852957, password Secret123! (force the model to load).", timeoutMs);
            log.info("[bench] Rust warmup OK: {} findings", warm.size());
        } catch (RuntimeException e) {
            log.warn("[bench] Rust warmup failed (continuing): {}", e.toString());
        }
    }

    private void estimateRust(RustPiiDetectorClient client, List<GoldDoc> gold, long timeoutMs) {
        GoldDoc probe = gold.stream().min(Comparator.comparingInt(d -> d.text().length())).orElseThrow();
        Instant t = Instant.now();
        List<RawEntity> r = detectRustWithRetry(client, probe.id(), probe.text(), timeoutMs);
        long probeMs = Duration.between(t, Instant.now()).toMillis();
        if (r == null) {
            log.warn("[bench] Rust estimate probe failed on shortest doc {} — skipping ETA", probe.id());
            return;
        }
        long etaS = Math.round(probeMs / 1000.0 * gold.size());
        log.info("[bench] >>> Rust ESTIMATED RUN TIME: shortest doc {} ({} chars) took {}ms -> ~{} for {} docs <<<",
            probe.id(), probe.text().length(), probeMs, humanDuration(etaS), gold.size());
    }

    /** 3 attempts with backoff; null when all fail (counts as an HTTP failure for the doc). */
    private List<RawEntity> detectRustWithRetry(RustPiiDetectorClient client, String docId, String text, long timeoutMs) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return client.detect(text, timeoutMs);
            } catch (RuntimeException t) {
                if (attempt == maxAttempts) {
                    log.warn("[bench] Rust detect failed for {}: {}", docId, t.toString());
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

    private static synchronized RustPiiDetectorClient ensureRustStarted() {
        if (rustClient != null) {
            return rustClient;
        }
        if (!rustModelPresent()) {
            return null;
        }
        log.info("[bench] starting Rust detector container (model: {}, NER-only override)", rustModelDir());
        // Run with the ner-only override so ONLY the gliner-large NER layer is active
        // (regex layer disabled). The image ENTRYPOINT is /app/grpc_server; these are
        // its args. The override TOML is baked into the image at /app/config/overrides.
        rustDetector = new GenericContainer<>(buildRustImage())
            .withExposedPorts(GRPC_PORT)
            .withFileSystemBind(rustModelDir().toString(), RUST_MODEL_MOUNT, BindMode.READ_ONLY)
            .withCommand(
                "--taxonomy", "/app/config/nlpd-ipi.toml",
                "--override-path", "/app/config/overrides/ner-only.toml",
                "--model", RUST_MODEL_MOUNT,
                "--addr", "0.0.0.0:" + GRPC_PORT)
            .withLogConsumer(Gliner2VsGlinerRustVsMinistralBenchmarkIT::routeRustLog)
            .waitingFor(Wait.forLogMessage(".*Server started on port.*", 1))
            .withStartupTimeout(Duration.ofMinutes(10));
        rustDetector.start();
        rustClient = new RustPiiDetectorClient(rustDetector.getHost(), rustDetector.getMappedPort(GRPC_PORT));
        log.info("[bench] Rust detector ready at {}:{}", rustDetector.getHost(), rustDetector.getMappedPort(GRPC_PORT));
        return rustClient;
    }

    // ---- ministral evaluation (endpoint) -------------------------------------

    private ExtractorReport.ModelEval evaluateMinistral(List<GoldDoc> gold) throws IOException {
        ExtractorModel model = ministralModel();
        LlmExtractorClient client = newLlmClient();
        ExtractorConceptMap extractorMap = loadExtractorMap();
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

    private boolean endpointReachable(LlmExtractorClient client, ExtractorModel model) {
        try {
            Set<String> served = client.listModelIds(model.baseUrl(), PREFLIGHT_TIMEOUT);
            log.info("[smoke ministral] endpoint {} reachable, serves {} model(s)", model.baseUrl(), served.size());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            log.warn("[smoke ministral] endpoint {} unreachable: {}", model.baseUrl(), e.toString());
            return false;
        }
    }

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

    private static ExtractorConceptMap loadExtractorMap() {
        try {
            return ExtractorConceptMap.load(extractorMapPath());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load extractor concept map: " + extractorMapPath(), e);
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

    private static Path rustModelDir() {
        return Paths.get(System.getProperty(
            "rust.bench.model-dir",
            System.getProperty("user.home") + "/.ai-sentinel-it-hf-cache/gliner2-large-v1-onnx")).toAbsolutePath();
    }

    private static boolean rustModelPresent() {
        Path d = rustModelDir();
        return Files.isDirectory(d)
            && Files.isRegularFile(d.resolve("gliner2_config.json"))
            && Files.isRegularFile(d.resolve("tokenizer.json"))
            && Files.isRegularFile(d.resolve("onnx/encoder.onnx"));
    }

    private static Duration rustTimeout() {
        return Duration.ofSeconds(intProp("bench.rust.request-timeout-s", 120));
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

    // ---- container infra -----------------------------------------------------

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
        // Same image name as the other detector ITs so the built image is shared/cached.
        return new ImageFromDockerfile("ai-sentinel-pii-detector-it", false)
            .withFileFromPath("pii-detector-service/Dockerfile", detectorRoot.resolve("Dockerfile"))
            .withFileFromPath("pii-detector-service/pyproject.toml", detectorRoot.resolve("pyproject.toml"))
            .withFileFromPath("pii-detector-service/README.md", detectorRoot.resolve("README.md"))
            .withFileFromPath("pii-detector-service/pii_detector", detectorRoot.resolve("pii_detector"))
            .withFileFromPath("pii-detector-service/config", detectorRoot.resolve("config"))
            .withFileFromPath("pii-detector-service/docker-entrypoint.sh", detectorRoot.resolve("docker-entrypoint.sh"))
            .withFileFromPath("proto", repoRoot.resolve("proto"))
            .withDockerfilePath("pii-detector-service/Dockerfile");
    }

    private static ImageFromDockerfile buildRustImage() {
        Path rustRoot = Paths.get("..").toAbsolutePath().normalize().resolve("pii-detector-rust");
        // Context = the module dir; the Dockerfile's COPY paths are module-relative
        // and the model is bind-mounted at runtime (never part of the build context).
        return new ImageFromDockerfile("ai-sentinel-pii-detector-rust-it", false)
            .withFileFromPath(".", rustRoot);
    }

    private static void routePythonLog(OutputFrame frame) {
        routeContainerLog(PY_CONTAINER_LOG, frame);
    }

    private static void routeRustLog(OutputFrame frame) {
        routeContainerLog(RUST_CONTAINER_LOG, frame);
    }

    private static void routeContainerLog(Logger logger, OutputFrame frame) {
        String raw = frame.getUtf8String();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String line = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        if (line.contains(" - DEBUG - ")) {
            logger.debug(line);
        } else if (line.contains(" - WARNING - ") || line.contains(" - WARN - ")) {
            logger.warn(line);
        } else if (line.contains(" - ERROR - ") || line.contains(" - CRITICAL - ")) {
            logger.error(line);
        } else {
            logger.info(line);
        }
    }
}
