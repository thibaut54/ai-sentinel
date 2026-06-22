package pro.softcom.aisentinel.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
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
import pro.softcom.aisentinel.integration.bench.BenchConfig;
import pro.softcom.aisentinel.integration.bench.BenchmarkReport;
import pro.softcom.aisentinel.integration.bench.ConceptMap;
import pro.softcom.aisentinel.integration.bench.DetectorConfigSeed;
import pro.softcom.aisentinel.integration.bench.Finding;
import pro.softcom.aisentinel.integration.bench.GoldDataset;
import pro.softcom.aisentinel.integration.bench.GoldDoc;
import pro.softcom.aisentinel.integration.bench.ScoreResult;
import pro.softcom.aisentinel.integration.bench.SpanScorer;

import javax.sql.DataSource;
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
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;

/**
 * Span-level benchmark of the PII detector pipeline against labelled HF datasets
 * (spec {@code my-files/integratoin-test-with-dataset/spec.md}).
 *
 * <p>Measures precision/recall/F1 (exact char-offset match) for each detector in
 * isolation (GLINER2, PRESIDIO, REGEX, OPENMED), for the full union ("pipeline"),
 * and the LLM-as-judge's impact on each. It reuses the Testcontainers infra of
 * {@link CorpusDataSqlComparisonIT} (Postgres + the Python pii-detector served
 * over gRPC) and the DB config to enable detectors and route the judge.
 *
 * <h2>Judge ON/OFF in a single scan</h2>
 * With {@code prefilter_enabled=false} and the judge being drop-only, one
 * judge-on scan yields both states from the same response:
 * <ul>
 *   <li>judge ON  = {@code sensitiveDataFound()} (kept after the judge);</li>
 *   <li>judge OFF = {@code sensitiveDataFound() ∪ discardedByJudge()} (pre-judge).</li>
 * </ul>
 * If the judge endpoint is unreachable it runs fail-open and discards nothing, so
 * a zero total-discard count means the judge half is invalid — this is asserted
 * (the validity guard, spec decision "vrai endpoint LAN + garde-fou").
 *
 * <h2>Prerequisites</h2>
 * <ol>
 *   <li>Gold built: {@code python benchmarks/pii-dataset-eval/build_datasets.py} (see its README).</li>
 *   <li>A reachable OpenAI-compatible judge endpoint (LM Studio). Override with
 *       {@code -Dcorpus.bench.llm-judge-url=http://<host>:1234/v1} and
 *       {@code -Dcorpus.bench.llm-judge-model=detect-pii-4b-v2}.</li>
 * </ol>
 *
 * <h2>Run</h2>
 * IntelliJ run configs (under {@code .run/}, env {@code RUN_PII_BENCHMARK=true} preset):
 * <ul>
 *   <li>{@code PiiDetectorBenchmarkIT smoke} — only {@link #smokePipelineProducesFindings()};
 *       fastest way to validate the image build + wiring.</li>
 *   <li>{@code PiiDetectorBenchmarkIT quick} — full class capped to a few docs
 *       ({@code corpus.bench.max-docs}), judge guard off; fast end-to-end + report.</li>
 *   <li>{@code PiiDetectorBenchmarkIT} — the real full-gold run.</li>
 * </ul>
 * Or via Maven:
 * <pre>
 *   $env:RUN_PII_BENCHMARK = "true"
 *   mvn -Dtest=PiiDetectorBenchmarkIT "-Dcorpus.bench.hf-cache=C:\hf-cache" \
 *       "-Dcorpus.bench.llm-judge-url=http://host.docker.internal:1234/v1" test
 * </pre>
 * Useful system properties: {@code corpus.bench.gold-dir}, {@code corpus.bench.concept-map},
 * {@code corpus.bench.max-docs} (smoke), {@code corpus.bench.threshold},
 * {@code corpus.bench.require-judge-effect} (default true).
 */
@Testcontainers
@SpringBootTest(classes = AiSentinelApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "RUN_PII_BENCHMARK", matches = "true")
class PiiDetectorBenchmarkIT {

    private static final Logger log = LoggerFactory.getLogger(PiiDetectorBenchmarkIT.class);
    private static final org.slf4j.Logger CONTAINER_LOG = LoggerFactory.getLogger("pii-detector-container");

    private static final int GRPC_PORT = 50051;
    private static final String POSTGRES_ALIAS = "postgres-it";
    private static final String DB_NAME = "ai-sentinel";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";

    private static final String MASTER_SEED = "classpath:sql/data-improved-gliner2-presidio-regex.sql";
    private static final String OUTPUT_ROOT = "target/pii-bench";

    /**
     * Short synthetic text mixing several PII types (IBAN, password, IP, AVS,
     * credit card) so the smoke test can assert the full pipeline produces
     * findings without paying the cost of the whole gold set. Explicit labels
     * help the ML detectors (GLiNER2, OpenMed) anchor the context.
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

    /** Judge endpoint: default to the dev host's LM Studio; override per environment. */
    private static final String JUDGE_URL =
        System.getProperty("corpus.bench.llm-judge-url", "http://host.docker.internal:1234/v1");
    private static final String JUDGE_MODEL =
        System.getProperty("corpus.bench.llm-judge-model", "detect-pii-4b-v2");

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
        // LLM-judge endpoint. audit_sources is NOT set here on purpose: it is
        // derived per-request from the DB *_judge_enabled flags (which override
        // the env), seeded per config by DetectorConfigSeed.
        .withEnv("LLM_JUDGE_BASE_URL", JUDGE_URL)
        .withEnv("LLM_JUDGE_PREFERRED_MODEL", JUDGE_MODEL)
        .withLogConsumer(PiiDetectorBenchmarkIT::routeContainerLog)
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
     * Smoke test: reseed the master config with the full pipeline (all detectors
     * enabled) and analyze one short synthetic text, asserting the pipeline wiring
     * (Testcontainers image build + Postgres reseed + gRPC pii-detector) works and
     * produces at least one finding — without paying the cost of the whole gold
     * set. Runs first ({@code @Order(0)}) so a broken image/wiring fails fast.
     *
     * <p>Judge-agnostic on purpose: it only checks raw detection, so it passes
     * even when the LLM-judge endpoint is unreachable (the judge-impact guard
     * lives in {@link #runBenchmark()}).
     *
     * <p>Run isolated:
     * <pre>mvn -Dtest=PiiDetectorBenchmarkIT#smokePipelineProducesFindings ... test</pre>
     */
    @Test
    @Order(0)
    void smokePipelineProducesFindings() throws Exception {
        log.info("[smoke] === START ===");
        resetAndReseedDb(MASTER_SEED);
        DetectorConfigSeed.apply(jdbcTemplate, BenchConfig.pipeline(), null);

        Instant t0 = Instant.now();
        ContentPiiDetection detection = piiDetectorClient.analyzeContent(SMOKE_TEXT);
        long elapsedS = Duration.between(t0, Instant.now()).toSeconds();

        assertThat(detection).as("analyzeContent returned null").isNotNull();
        assertThat(detection.sensitiveDataFound()).as("sensitiveDataFound is null").isNotNull();

        Map<String, Long> byDetector = detection.sensitiveDataFound().stream()
            .collect(Collectors.groupingBy(sd -> sd.source().name(), Collectors.counting()));
        log.info("[smoke] pipeline -> {} findings in {}s, by detector: {}",
            detection.sensitiveDataFound().size(), elapsedS, byDetector);

        assertThat(detection.sensitiveDataFound())
            .as("the full pipeline produced 0 findings on the smoke text — the image build, a detector "
                + "model load, or the gRPC wiring is broken")
            .isNotEmpty();
        log.info("[smoke] === DONE ===");
    }

    @Test
    @Order(1)
    void runBenchmark() throws Exception {
        Path goldDir = Paths.get(System.getProperty(
            "corpus.bench.gold-dir", "../benchmarks/pii-dataset-eval/gold")).toAbsolutePath();
        Path conceptMapPath = Paths.get(System.getProperty(
            "corpus.bench.concept-map",
            "../benchmarks/pii-dataset-eval/mappings/detector_concept_map.json")).toAbsolutePath();

        List<GoldDoc> gold = loadGold(goldDir);
        log.info("[bench] loaded {} gold docs from {}", gold.size(), goldDir);
        assertThat(gold).as("gold dataset must be built first (see benchmarks/pii-dataset-eval/README.md)")
            .isNotEmpty();
        ConceptMap conceptMap = ConceptMap.load(conceptMapPath);

        Double thresholdOverride = parseDoubleProperty("corpus.bench.threshold");

        List<BenchConfig> configs = List.of(
            BenchConfig.isolated(DetectorSource.GLINER2),
            BenchConfig.isolated(DetectorSource.PRESIDIO),
            BenchConfig.isolated(DetectorSource.REGEX),
            BenchConfig.isolated(DetectorSource.OPENMED),
            BenchConfig.pipeline());

        warmUpAndEstimate(gold, configs.size());

        List<BenchmarkReport.ConfigEvaluation> evals = new ArrayList<>();
        int totalDiscarded = 0;
        Instant start = Instant.now();
        int globalTotal = gold.size() * configs.size();
        int globalDone = 0;

        for (BenchConfig config : configs) {
            log.info("[bench] === config {} (detectors={}) ===", config.name(), config.detectors());
            resetAndReseedDb(MASTER_SEED);
            pro.softcom.aisentinel.integration.bench.DetectorConfigSeed.apply(jdbcTemplate, config, thresholdOverride);

            Map<String, List<Finding>> findingsOn = new LinkedHashMap<>();
            Map<String, List<Finding>> findingsOff = new LinkedHashMap<>();
            int discarded = 0;
            int failed = 0;
            int idx = 0;

            for (GoldDoc doc : gold) {
                idx++;
                globalDone++;
                Instant tDoc = Instant.now();
                ContentPiiDetection detection = analyzeWithRetry(config.name(), doc.id(), doc.text());
                long docS = Duration.between(tDoc, Instant.now()).toSeconds();
                if (detection == null) {
                    failed++;
                    log.warn("[bench] {} [{}/{}] {} : FAILED ({}s) | run {}/{} ETA ~{}",
                        config.name(), idx, gold.size(), doc.id(), docS,
                        globalDone, globalTotal, etaFor(start, globalDone, globalTotal));
                    continue;
                }
                List<Finding> kept = detection.sensitiveDataFound().stream()
                    .map(sd -> Finding.from(sd, conceptMap)).toList();
                List<Finding> off = new ArrayList<>(kept);
                detection.discardedByJudge().forEach(d -> off.add(Finding.from(d.data(), conceptMap)));
                findingsOn.put(doc.id(), kept);
                findingsOff.put(doc.id(), off);
                discarded += detection.discardedByJudge().size();
                log.info("[bench] {} [{}/{}] {} : {} kept, {} judged-out ({}s) | run {}/{} ETA ~{}",
                    config.name(), idx, gold.size(), doc.id(), kept.size(),
                    detection.discardedByJudge().size(), docS,
                    globalDone, globalTotal, etaFor(start, globalDone, globalTotal));
            }

            ScoreResult scoreOn = SpanScorer.score(gold, findingsOn);
            ScoreResult scoreOff = SpanScorer.score(gold, findingsOff);
            evals.add(new BenchmarkReport.ConfigEvaluation(config, scoreOff, scoreOn, discarded));
            totalDiscarded += discarded;
            log.info("[bench] {} done: judge-on F1={} judge-off F1={} discarded={} failed={}",
                config.name(), scoreOn.strictOverall().f1(), scoreOff.strictOverall().f1(), discarded, failed);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("gold_docs", gold.size());
        meta.put("gold_dir", goldDir);
        meta.put("judge_url", JUDGE_URL);
        meta.put("judge_model", JUDGE_MODEL);
        meta.put("threshold_override", thresholdOverride == null ? "seed defaults" : thresholdOverride);
        meta.put("total_judge_discards", totalDiscarded);
        meta.put("duration_s", Duration.between(start, Instant.now()).toSeconds());

        Path outDir = Paths.get(OUTPUT_ROOT).toAbsolutePath();
        BenchmarkReport.write(outDir, evals, meta);
        log.info("[bench] === report written to {} ===", outDir);

        assertThat(outDir.resolve("report.md")).exists();

        boolean requireJudgeEffect = Boolean.parseBoolean(
            System.getProperty("corpus.bench.require-judge-effect", "true"));
        if (requireJudgeEffect) {
            assertThat(totalDiscarded)
                .as("LLM-judge discarded 0 findings across ALL configs — the judge endpoint (%s) is most "
                    + "likely unreachable from the container and ran fail-open, so the judge-impact half of "
                    + "the benchmark is INVALID. Fix reachability or set -Dcorpus.bench.require-judge-effect=false "
                    + "to acknowledge.", JUDGE_URL)
                .isPositive();
        }
    }

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

    /**
     * Seeds the full pipeline, forces all detector models to load (warmup), then
     * probes the shortest gold doc once to project the full run duration BEFORE
     * committing to it — mirrors {@code LlmExtractorComparisonIT}. The estimate is
     * an UPPER bound: the probe runs the full pipeline (all detectors) and is
     * multiplied across every doc and every config, while the isolated
     * single-detector configs are faster. The main loop reseeds per config, so the
     * pipeline seed set here is transient.
     */
    private void warmUpAndEstimate(List<GoldDoc> gold, int nConfigs) throws SQLException {
        resetAndReseedDb(MASTER_SEED);
        DetectorConfigSeed.apply(jdbcTemplate, BenchConfig.pipeline(), null);
        warmup();
        GoldDoc probe = gold.stream()
            .min(Comparator.comparingInt(d -> d.text().length()))
            .orElseThrow();
        Instant tProbe = Instant.now();
        ContentPiiDetection r = analyzeWithRetry("estimate", probe.id(), probe.text());
        long probeMs = Duration.between(tProbe, Instant.now()).toMillis();
        if (r == null) {
            log.warn("[bench] estimate probe failed on shortest doc {} — skipping ETA estimate", probe.id());
            return;
        }
        long totalEtaS = Math.round(probeMs / 1000.0 * gold.size() * nConfigs);
        log.info("[bench] estimate: shortest doc {} ({} chars) took {}ms on the full pipeline",
            probe.id(), probe.text().length(), probeMs);
        log.info("[bench] >>> ESTIMATED TOTAL RUN TIME for {} docs x {} configs: ~{} "
            + "(upper bound — isolated single-detector configs run faster than the full-pipeline probe) <<<",
            gold.size(), nConfigs, humanDuration(totalEtaS));
    }

    private void warmup() {
        try {
            ContentPiiDetection warm = piiDetectorClient.analyzeContent(
                "Warmup: IBAN CH9300762011623852957, password Secret123! (force detectors to load).");
            log.info("[bench] warmup OK: {} findings", warm.sensitiveDataFound().size());
        } catch (RuntimeException e) {
            log.warn("[bench] warmup failed (continuing): {}", e.toString());
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
        if (m < 60) {
            return s == 0 ? m + "m" : m + "m " + s + "s";
        }
        long h = m / 60;
        m = m % 60;
        return String.format(Locale.ROOT, "%dh%02dm", h, m);
    }

    /** 3 attempts with backoff; null when all fail. Mirrors CorpusDataSqlComparisonIT. */
    private ContentPiiDetection analyzeWithRetry(String config, String docId, String text) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return piiDetectorClient.analyzeContent(text);
            } catch (RuntimeException t) {
                String msg = t.toString();
                if (msg.contains("INVALID_ARGUMENT") || msg.contains("Content too large") || attempt == maxAttempts) {
                    log.warn("[{}] analyze failed for {}: {}", config, docId, msg);
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

    private void resetAndReseedDb(String sqlClasspath) throws SQLException {
        jdbcTemplate.execute("DELETE FROM pii_type_config");
        jdbcTemplate.execute("DELETE FROM pii_detection_config");
        Resource resource = new DefaultResourceLoader().getResource(sqlClasspath);
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new EncodedResource(resource, StandardCharsets.UTF_8));
        }
    }

    private static Double parseDoubleProperty(String key) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? null : Double.parseDouble(v);
    }

    private static Integer parseIntProperty(String key) {
        String v = System.getProperty(key);
        return (v == null || v.isBlank()) ? null : Integer.parseInt(v);
    }

    // ---- Container infra (mirrors CorpusDataSqlComparisonIT) -----------------

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
        return new ImageFromDockerfile("ai-sentinel-pii-detector-it", false)
            .withFileFromPath("pii-detector-service/Dockerfile", detectorRoot.resolve("Dockerfile"))
            .withFileFromPath("pii-detector-service/pyproject.toml", detectorRoot.resolve("pyproject.toml"))
            .withFileFromPath("pii-detector-service/README.md", detectorRoot.resolve("README.md"))
            .withFileFromPath("pii-detector-service/pii_detector", detectorRoot.resolve("pii_detector"))
            .withFileFromPath("pii-detector-service/config", detectorRoot.resolve("config"))
            .withFileFromPath("pii-detector-service/docker-entrypoint.sh", detectorRoot.resolve("docker-entrypoint.sh"))
            .withFileFromPath("proto", repoRoot.resolve("proto"))
            // The Dockerfile's COPY paths are repo-root-relative (COPY
            // pii-detector-service/pyproject.toml, COPY proto), matching the
            // docker-compose build context. withDockerfilePath points at the
            // Dockerfile *inside* the assembled context above, keeping that
            // repo-root-shaped context. withDockerfile(Path) must NOT be used: it
            // resets the build context to the Dockerfile's parent
            // (pii-detector-service/), so COPY pii-detector-service/pyproject.toml
            // then resolves to a non-existent nested path and the build fails with
            // "pii-detector-service/pyproject.toml: file does not exist".
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
