package pro.softcom.aisentinel.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.tika.Tika;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pro.softcom.aisentinel.AiSentinelApplication;
import pro.softcom.aisentinel.application.pii.reporting.service.parser.HtmlContentParser;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autonomous corpus benchmark — full pipeline (Java backend + Python pii-detector + Postgres),
 * driven entirely by Testcontainers so the test runs from a fresh checkout without manual setup.
 *
 * <p><strong>How to use it</strong>
 * <pre>
 *   # First run will be slow: builds the Python image (~5 min) and downloads the GLiNER model (~2GB)
 *   mvn -pl pii-reporting-api -Dtest=CorpusBenchmarkIT \
 *       -DRUN_CORPUS_BENCHMARK=true \
 *       -Dbench.label=baseline \
 *       test
 *
 *   # Tweak code / config, then re-run with a different label and diff
 *   mvn -pl pii-reporting-api -Dtest=CorpusBenchmarkIT \
 *       -DRUN_CORPUS_BENCHMARK=true \
 *       -Dbench.label=after-chunking-fix \
 *       test
 *
 *   python pii-reporting-api/scripts/compare-bench.py \
 *       pii-reporting-api/target/corpus-bench-baseline.tsv \
 *       pii-reporting-api/target/corpus-bench-after-chunking-fix.tsv
 * </pre>
 *
 * <p><strong>Why we override the Docker entrypoint</strong>: the production image fetches its
 * configuration from Infisical via {@code docker-entrypoint.sh}. Running Infisical inside this
 * test would require its own Postgres + Redis + bootstrap dance. Instead, we replace the
 * entrypoint and inject DB credentials directly via env vars.
 *
 * <p><strong>Why we mount a named volume on {@code /app/.cache/huggingface}</strong>: GLiNER
 * downloads a ~2 GB model from HuggingFace on first inference. Without this volume, every test
 * run pays that cost. The named volume {@code ai-sentinel-it-hf-cache} survives Testcontainers
 * cleanup and is shared across runs.
 */
@Testcontainers
@SpringBootTest(
    classes = AiSentinelApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
//@EnabledIfEnvironmentVariable(named = "RUN_CORPUS_BENCHMARK", matches = "true")
class CorpusBenchmarkIT {

    private static final Logger log = LoggerFactory.getLogger(CorpusBenchmarkIT.class);

    private static final String CORPUS_ROOT = "src/test/resources/test-corpus";
    /**
     * Persistent host directory used to cache the GLiNER model (~2 GB) downloaded from
     * HuggingFace on first inference. Survives Testcontainers cleanup so subsequent test
     * runs do not pay the download cost again. Defaults to {@code D:\ai-sentinel-it-hf-cache}
     * on this developer machine because the C: drive is constrained; override via the
     * {@code corpus.bench.hf-cache} system property to relocate.
     */
    private static final Path HF_CACHE_DIR = Paths.get(
        System.getProperty("corpus.bench.hf-cache",
            isWindows() && Files.exists(Paths.get("D:/")) ? "D:/ai-sentinel-it-hf-cache"
                : System.getProperty("user.home") + "/.ai-sentinel-it-hf-cache"));
    private static final int GRPC_PORT = 50051;

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
    private static final String POSTGRES_ALIAS = "postgres-it";
    private static final String DB_NAME = "ai-sentinel";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";

    /**
     * Shared docker network so the pii-detector container can resolve {@code postgres-it:5432}
     * directly without going through the host bridge — this avoids host.docker.internal quirks
     * on Linux CI runners and keeps the container-to-container path predictable.
     */
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USER)
            .withPassword(DB_PASSWORD)
            .withNetwork(NETWORK)
            .withNetworkAliases(POSTGRES_ALIAS);

    /**
     * Python pii-detector built locally from the project Dockerfile so the test stays
     * autonomous (no need to authenticate with the GHCR registry). The build context is
     * assembled from explicit subpaths to avoid tarring the 5+ GB of dev artefacts
     * (models/, .venv/, .pnpm-store/, ...) that live alongside the source. Each path
     * mirrors what the production Dockerfile actually COPYs.
     */
    @Container
    static final GenericContainer<?> piiDetector = new GenericContainer<>(buildPiiDetectorImage())
        // Bypass docker-entrypoint.sh which requires Infisical; run the Python server directly.
        .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(""))
        .withCommand("python", "-m", "pii_detector.server", "--port", String.valueOf(GRPC_PORT))
        .withExposedPorts(GRPC_PORT)
        .withNetwork(NETWORK)
        .withNetworkAliases("pii-detector")
        .withFileSystemBind(ensureHfCacheDir(), "/app/.cache/huggingface")
        .withEnv("HF_HOME", "/app/.cache/huggingface")
        .withEnv("TRANSFORMERS_CACHE", "/app/.cache/huggingface")
        // Postgres reachable via the shared network alias on its native port (5432, not the
        // mapped host port). Credentials are static and fixed at @Container declaration time.
        .withEnv("DB_HOST", POSTGRES_ALIAS)
        .withEnv("DB_PORT", "5432")
        .withEnv("DB_NAME", DB_NAME)
        .withEnv("DB_USER", DB_USER)
        .withEnv("DB_PASSWORD", DB_PASSWORD)
        .waitingFor(Wait.forLogMessage(".*Server started on port.*", 1))
        .withStartupTimeout(java.time.Duration.ofMinutes(10));

    private static String ensureHfCacheDir() {
        try {
            Files.createDirectories(HF_CACHE_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create HF cache dir: " + HF_CACHE_DIR, e);
        }
        return HF_CACHE_DIR.toString();
    }

    /**
     * Builds an {@link ImageFromDockerfile} whose context only contains the files the
     * Dockerfile actually needs. The test root for this module is {@code pii-reporting-api/},
     * so {@code ../pii-detector-service/} resolves at the repo root.
     */
    private static ImageFromDockerfile buildPiiDetectorImage() {
        Path repoRoot = Paths.get("..").toAbsolutePath().normalize();
        Path detectorRoot = repoRoot.resolve("pii-detector-service");
        return new ImageFromDockerfile("ai-sentinel-pii-detector-it", false)
            .withFileFromPath("pii-detector-service/Dockerfile",
                detectorRoot.resolve("Dockerfile"))
            .withFileFromPath("pii-detector-service/pyproject.toml",
                detectorRoot.resolve("pyproject.toml"))
            .withFileFromPath("pii-detector-service/README.md",
                detectorRoot.resolve("README.md"))
            .withFileFromPath("pii-detector-service/pii_detector",
                detectorRoot.resolve("pii_detector"))
            .withFileFromPath("pii-detector-service/config",
                detectorRoot.resolve("config"))
            .withFileFromPath("pii-detector-service/docker-entrypoint.sh",
                detectorRoot.resolve("docker-entrypoint.sh"))
            .withFileFromPath("proto", repoRoot.resolve("proto"))
            .withDockerfilePath("pii-detector-service/Dockerfile");
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Postgres: Spring Boot connects to the host-mapped port from the JVM running the test
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.jpa.properties.hibernate.dialect",
            () -> "org.hibernate.dialect.PostgreSQLDialect");

        // gRPC backend: route the Java client to the pii-detector container's host-mapped port
        registry.add("pii-detector.host", piiDetector::getHost);
        registry.add("pii-detector.port", () -> piiDetector.getMappedPort(GRPC_PORT));

        // Larger gRPC deadline than the 5s default so PyTorch CPU inference on a fresh
        // model load actually has time to complete on long inputs.
        registry.add("pii-detector.connection-timeout-ms", () -> "300000");
        registry.add("pii-detector.request-timeout-ms", () -> "300000");

        // Secrets normally fetched from Infisical
        registry.add("PII_DATABASE_ENCRYPTION_KEY",
            () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="); // 32-byte zero key (test only)
        registry.add("PII_REPORTING_ALLOW_SECRET_REVEAL", () -> "false");
    }

    @Autowired
    private PiiDetectorClient piiDetectorClient;

    @Autowired
    private HtmlContentParser htmlContentParser;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * GLiNER detector_label -> internal pii_type for the parity test config. The keys are the
     * exact labels NVIDIA's playground used to produce the reference findings; the values are
     * the rows we want enabled in {@code pii_type_config}. Order is preserved for log clarity.
     */
    private static final Map<String, String> PARITY_LABEL_TO_PII_TYPE;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("customer_id",                     "ACCOUNT_ID");
        m.put("api_key",                         "API_KEY");
        m.put("account_number",                  "BANK_ACCOUNT_NUMBER");
        m.put("swift_bic",                       "BIC_SWIFT");
        m.put("device_identifier",               "DEVICE_ID");
        m.put("certificate_license_number",      "DRIVER_LICENSE_NUMBER");
        m.put("health_plan_beneficiary_number",  "HEALTH_INSURANCE_NUMBER");
        m.put("medical_record_number",           "MEDICAL_RECORD_NUMBER");
        m.put("national_id",                     "NATIONAL_ID");
        m.put("password",                        "PASSWORD");
        m.put("http_cookie",                     "SESSION_ID");
        m.put("ssn",                             "SSN");
        m.put("license_plate",                   "LICENSE_PLATE");
        PARITY_LABEL_TO_PII_TYPE = m;
    }

    /**
     * Smoke test against a single mid-sized file from the corpus. Targeted with
     * {@code mvn ... -Dtest=CorpusBenchmarkIT#smokeTest_singleFile}. Override the file
     * path with {@code -Dcorpus.smoke.file=<relative-path-under-test-corpus>}.
     *
     * <p>Goes through the production HTML cleaning pipeline ({@link HtmlContentParser})
     * so the test exercises the same code path that runs at scan time on Confluence pages,
     * not just a raw Tika extraction. Tika is still used as a fallback for non-HTML inputs.
     */
    @Test
    void smokeTest_singleFile() throws Exception {
        // === DEBUG: validate every wiring step before we touch the network ===
        log.info("[SMOKE][SETUP] postgres TC running={}, jdbcUrl={}",
            postgres.isRunning(), postgres.getJdbcUrl());
        assertThat(postgres.isRunning()).as("postgres testcontainer up").isTrue();

        log.info("[SMOKE][SETUP] pii-detector TC running={}, host={}, mappedPort={}",
            piiDetector.isRunning(), piiDetector.getHost(), piiDetector.getMappedPort(GRPC_PORT));
        assertThat(piiDetector.isRunning()).as("pii-detector testcontainer up").isTrue();

        log.info("[SMOKE][SETUP] HF cache dir={} exists={}",
            HF_CACHE_DIR, Files.exists(HF_CACHE_DIR));
        assertThat(Files.exists(HF_CACHE_DIR)).as("HF cache dir exists").isTrue();

        log.info("[SMOKE][SETUP] piiDetectorClient bean class={}",
            piiDetectorClient != null ? piiDetectorClient.getClass().getName() : "null");
        assertThat(piiDetectorClient).as("PiiDetectorClient autowired").isNotNull();

        log.info("[SMOKE][SETUP] htmlContentParser bean class={}",
            htmlContentParser != null ? htmlContentParser.getClass().getName() : "null");
        assertThat(htmlContentParser).as("HtmlContentParser autowired").isNotNull();

        // Round-trip the gRPC client on a tiny payload so we fail fast if the channel
        // is mis-wired before paying for a full Tika+HTML extraction.
        log.info("[SMOKE][SETUP] gRPC ping with 'ping@example.com' ...");
        ContentPiiDetection ping = piiDetectorClient.analyzeContent("ping@example.com");
        log.info("[SMOKE][SETUP] gRPC ping OK, findings={}", ping.sensitiveDataFound().size());
        assertThat(ping).as("gRPC ping reachable").isNotNull();

        // === END SETUP ASSERTIONS ===

        String relPath = System.getProperty(
            "corpus.smoke.file",
            "MEDICAL_LICENSE/04_SIRA_Reprise_de_donnees_Estimation_1230569554.html"
        );
        Path target = Paths.get(CORPUS_ROOT, relPath).toAbsolutePath();
        assertThat(target).as("smoke target file").exists();
        log.info("[SMOKE] analyzing {}", target);

        // Read raw HTML and run it through the production HtmlContentParser so the test
        // covers exactly the same cleaning logic that runs in StreamConfluenceScanUseCase.
        String rawHtml = Files.readString(target);
        log.info("[SMOKE] raw HTML size: {} chars", rawHtml.length());
        String cleaned = htmlContentParser.cleanText(rawHtml);
        log.info("[SMOKE] cleaned text size: {} chars (stripped {} chars of markup)",
            cleaned.length(), rawHtml.length() - cleaned.length());
        assertThat(cleaned).as("cleaned text non-empty").isNotBlank();

        long t0 = System.nanoTime();
        ContentPiiDetection detection = piiDetectorClient.analyzeContent(cleaned);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("[SMOKE] gRPC roundtrip: {} ms, {} findings",
            elapsedMs, detection.sensitiveDataFound().size());

        // Print every finding so the human can eyeball the quality
        detection.sensitiveDataFound().forEach(sd ->
            log.info("[SMOKE] {} score={} pos={}-{} ctx='{}'",
                sd.type(),
                sd.score() != null ? String.format("%.3f", sd.score()) : "n/a",
                sd.position(), sd.end(),
                sd.context())
        );

        // We don't assert a specific count — the goal is just to confirm the round trip
        // works and the model returned something. If the file genuinely has no PII the
        // assertion below will surface that and the human can pick a different file.
        assertThat(detection.sensitiveDataFound())
            .as("at least one finding on smoke file " + relPath)
            .isNotEmpty();
    }

    /**
     * Parity test against the NVIDIA GLiNER playground reference output.
     *
     * <p>Loads {@code my-files/confluence-pii-test-document-nvidia-gliner-result.json} (250
     * findings produced by the NVIDIA hosted playground on the same input file with the
     * same 13 GLiNER labels), runs the document through our full pipeline, and compares
     * the per-label finding counts side-by-side. This surfaces gaps between the hosted
     * model's raw output and what our multi-pass + threshold + LLM-validator pipeline
     * actually returns to the user.
     *
     * <p>The DB seed is mutated at runtime so exactly the 13 labels listed in
     * {@link #PARITY_LABEL_TO_PII_TYPE} are enabled (in particular: LICENSE_PLATE on
     * with a reachable threshold, VEHICLE_REGISTRATION off, SESSION_ID threshold lowered
     * from the default 1.0 so http_cookie can ever fire).
     */
    @Test
    void parityTest_confluencePiiTestDocument_shouldMatchNvidiaPlayground() throws Exception {
        // === Wiring sanity (same as smokeTest_singleFile) ===
        log.info("[PARITY][SETUP] postgres={}, piiDetector={}, client={}, parser={}, jdbc={}",
            postgres.isRunning(), piiDetector.isRunning(),
            piiDetectorClient != null, htmlContentParser != null, jdbcTemplate != null);
        assertThat(postgres.isRunning()).isTrue();
        assertThat(piiDetector.isRunning()).isTrue();
        assertThat(piiDetectorClient).isNotNull();
        assertThat(jdbcTemplate).isNotNull();

        // === Configure DB so only the 13 parity labels are enabled (GLINER detector) ===
        configureParityLabels();

        // === Load expected counts from the NVIDIA reference JSON ===
        Map<String, Integer> expectedByLabel = loadNvidiaExpectedCounts();
        int expectedTotal = expectedByLabel.values().stream().mapToInt(Integer::intValue).sum();
        log.info("[PARITY] expected total entities (NVIDIA reference): {}", expectedTotal);
        expectedByLabel.forEach((label, n) ->
            log.info("[PARITY]   expected {}={}", label, n));
        assertThat(expectedTotal).as("NVIDIA reference parsed").isGreaterThan(0);

        // === Read the corpus file (raw .txt, no HTML cleaning needed) ===
        Path target = Paths.get(
            CORPUS_ROOT, "Miscellaneous/confluence-pii-test-document-docanno.txt"
        ).toAbsolutePath();
        assertThat(target).as("parity corpus file").exists();
        String text = Files.readString(target);
        log.info("[PARITY] corpus file '{}' size={} chars", target.getFileName(), text.length());

        // === Run the full pipeline ===
        long t0 = System.nanoTime();
        ContentPiiDetection detection = piiDetectorClient.analyzeContent(text);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        log.info("[PARITY] gRPC roundtrip: {} ms, {} findings", elapsedMs,
            detection.sensitiveDataFound().size());

        // === Aggregate detected counts by GLiNER label ===
        // Our domain `type` is the pii_type (e.g. "ACCOUNT_ID"); we map it back to the GLiNER
        // label via PARITY_LABEL_TO_PII_TYPE so we can compare apples to apples.
        Map<String, String> piiTypeToLabel = new HashMap<>();
        PARITY_LABEL_TO_PII_TYPE.forEach((label, piiType) -> piiTypeToLabel.put(piiType, label));

        Map<String, Integer> detectedByLabel = new TreeMap<>();
        detection.sensitiveDataFound().forEach(sd -> {
            String label = piiTypeToLabel.getOrDefault(sd.type(), "(other:" + sd.type() + ")");
            detectedByLabel.merge(label, 1, Integer::sum);
        });

        // === Side-by-side comparison ===
        log.info("[PARITY] === DETECTED vs EXPECTED ===");
        log.info("[PARITY] {}", String.format("%-32s %8s %8s %8s", "label", "expected", "detected", "recall"));
        int detectedTotalForExpected = 0;
        Map<String, Double> recallByLabel = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : expectedByLabel.entrySet()) {
            String label = entry.getKey();
            int expected = entry.getValue();
            int detected = detectedByLabel.getOrDefault(label, 0);
            detectedTotalForExpected += Math.min(detected, expected);
            double recall = expected > 0 ? (double) detected / expected : 0.0;
            recallByLabel.put(label, recall);
            log.info("[PARITY] {}", String.format("%-32s %8d %8d %7.1f%%", label, expected, detected, recall * 100));
        }

        // Anything detected with a label NOT in the expected set surfaces as cross-type confusion
        detectedByLabel.entrySet().stream()
            .filter(e -> !expectedByLabel.containsKey(e.getKey()))
            .forEach(e -> log.info("[PARITY] (extra) {}",
                String.format("%-32s %8d", e.getKey(), e.getValue())));

        int detectedTotal = detection.sensitiveDataFound().size();
        double aggregateRecall = expectedTotal > 0
            ? (double) detectedTotalForExpected / expectedTotal : 0.0;
        log.info("[PARITY] aggregate recall: {} ({} / {})",
            String.format("%.1f%%", aggregateRecall * 100), detectedTotalForExpected, expectedTotal);
        log.info("[PARITY] detected total (incl. extras): {}", detectedTotal);

        // === Soft assertions — wide tolerances on purpose. The goal of this test is to
        // *guide iterations on labels and thresholds*, not to fail on small score deltas. ===
        assertThat(detectedTotal)
            .as("our pipeline returned at least one finding on the reference document")
            .isGreaterThan(0);
        assertThat(aggregateRecall)
            .as("aggregate recall vs NVIDIA reference (target >= 30%)")
            .isGreaterThanOrEqualTo(0.30);
    }

    private void configureParityLabels() {
        // 1. Disable every GLINER row first
        int disabled = jdbcTemplate.update(
            "UPDATE pii_type_config SET enabled = false WHERE detector = 'GLINER'");
        log.info("[PARITY][CONFIG] disabled {} GLINER rows", disabled);

        // 2. Enable only the 13 rows we want, with a reachable threshold (0.5) so SESSION_ID
        //    (default 1.0 = unreachable) and LICENSE_PLATE (default 1.0) actually fire.
        for (Map.Entry<String, String> entry : PARITY_LABEL_TO_PII_TYPE.entrySet()) {
            String label = entry.getKey();
            String piiType = entry.getValue();
            int updated = jdbcTemplate.update(
                "UPDATE pii_type_config SET enabled = true, threshold = 0.5, detector_label = ? "
                + "WHERE detector = 'GLINER' AND pii_type = ?",
                label, piiType);
            log.info("[PARITY][CONFIG] enabled {} -> {} ({} rows updated)", piiType, label, updated);
            assertThat(updated).as("pii_type " + piiType + " row exists").isEqualTo(1);
        }

        // 3. Make sure the global flag for GLINER is on (it is by default but be explicit)
        jdbcTemplate.update(
            "UPDATE pii_detection_config SET gliner_enabled = true WHERE id = 1");
    }

    private Map<String, Integer> loadNvidiaExpectedCounts() throws IOException {
        // The reference file is shared across modules at the repo root; resolve it relative
        // to the test working directory (pii-reporting-api/).
        Path expectedPath = Paths.get("..", "my-files",
            "confluence-pii-test-document-nvidia-gliner-result.json").toAbsolutePath().normalize();
        assertThat(expectedPath).as("NVIDIA reference JSON").exists();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode outer = mapper.readTree(expectedPath.toFile());
        // The chat-completion response wraps the JSON content in a string field
        JsonNode inner = mapper.readTree(outer.path("choices").get(0).path("message").path("content").asText());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonNode entity : inner.path("entities")) {
            counts.merge(entity.path("label").asText(), 1, Integer::sum);
        }
        return counts;
    }

    @Test
    void runCorpusBenchmark_shouldDetectPiiAcrossAllCategories() throws IOException {
        Path corpusRoot = Paths.get(CORPUS_ROOT).toAbsolutePath();
        assertThat(corpusRoot).as("corpus root").exists();

        String label = System.getProperty("bench.label", "run-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneId.systemDefault()).format(Instant.now()));
        log.info("[CORPUS_BENCH] starting bench with label='{}', corpus={}", label, corpusRoot);

        Tika tika = new Tika();
        List<FileResult> results = new ArrayList<>();
        Map<String, CategorySummary> perCategory = new LinkedHashMap<>();

        try (Stream<Path> categories = Files.list(corpusRoot).filter(Files::isDirectory).sorted()) {
            for (Path categoryDir : categories.toList()) {
                String categoryName = categoryDir.getFileName().toString();
                log.info("[CORPUS_BENCH] === category: {} ===", categoryName);
                CategorySummary summary = perCategory.computeIfAbsent(categoryName, k -> new CategorySummary());

                // Only run on .html files: each .doc has an .html sibling and is faster to extract.
                try (Stream<Path> files = Files.walk(categoryDir, 1)
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".html"))
                        .sorted(Comparator.comparing(Path::getFileName))) {
                    for (Path file : files.toList()) {
                        FileResult result = analyzeFile(tika, categoryName, file);
                        results.add(result);
                        summary.fileCount++;
                        summary.totalChars += result.charCount;
                        summary.totalFindings += result.findings.size();
                        summary.totalDurationMs += result.durationMs;
                    }
                }
                log.info("[CORPUS_BENCH] {} -> {} files, {} findings, {} ms total",
                    categoryName, summary.fileCount, summary.totalFindings, summary.totalDurationMs);
            }
        }

        writeReports(label, results, perCategory);
        log.info("[CORPUS_BENCH] DONE label={} files={} findings_total={}",
            label, results.size(), results.stream().mapToInt(r -> r.findings.size()).sum());
    }

    private FileResult analyzeFile(Tika tika, String categoryName, Path file) {
        FileResult fr = new FileResult();
        fr.category = categoryName;
        fr.fileName = file.getFileName().toString();
        try {
            // HTML inputs go through the production parser so the bench exercises the
            // same cleaning pipeline as a real Confluence page scan. Other formats fall
            // back to Tika's generic extraction.
            String text = file.getFileName().toString().toLowerCase().endsWith(".html")
                ? htmlContentParser.cleanText(Files.readString(file))
                : tika.parseToString(file.toFile());
            fr.charCount = text.length();
            long t0 = System.nanoTime();
            ContentPiiDetection detection = piiDetectorClient.analyzeContent(text);
            fr.durationMs = (System.nanoTime() - t0) / 1_000_000L;
            fr.findings = detection.sensitiveDataFound().stream().map(sd -> {
                Finding f = new Finding();
                f.type = sd.type();
                f.score = sd.score() != null ? sd.score() : 0.0;
                // Do NOT serialize sd.value() — corpus is real prod-like data, keep masked
                f.maskedContext = sd.context();
                f.startPosition = sd.position();
                f.endPosition = sd.end();
                return f;
            }).toList();
        } catch (Exception e) {
            log.warn("[CORPUS_BENCH] failed to analyze {}: {}", file, e.getMessage());
            fr.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return fr;
    }

    private void writeReports(String label, List<FileResult> results, Map<String, CategorySummary> perCategory)
            throws IOException {
        Path target = Paths.get("target");
        Files.createDirectories(target);

        // Detailed per-file JSON
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Path jsonPath = target.resolve("corpus-bench-" + label + ".json");
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("label", label);
            root.put("startedAt", Instant.now().toString());
            root.put("perCategory", perCategory);
            root.put("files", results);
            Files.writeString(jsonPath, mapper.writeValueAsString(root));
            log.info("[CORPUS_BENCH] JSON written: {}", jsonPath.toAbsolutePath());
        } catch (JsonProcessingException e) {
            log.error("[CORPUS_BENCH] failed to serialize JSON report", e);
        }

        // TSV in the same format as FileBenchRecorderAdapter so compare-bench.py works
        Path tsvPath = target.resolve("corpus-bench-" + label + ".tsv");
        StringBuilder tsv = new StringBuilder();
        tsv.append("timestamp\tlabel\tscanId\tspaceKey\tpageId\titemKind\tcharCount\tdurationMs\tcharsPerSec\tfindings\n");
        String now = Instant.now().toString();
        for (FileResult r : results) {
            double cps = r.durationMs > 0 ? (r.charCount * 1000.0) / r.durationMs : 0.0;
            tsv.append(now).append('\t')
               .append(label).append('\t')
               .append("corpus-bench").append('\t')
               .append(r.category).append('\t')
               .append(r.fileName).append('\t')
               .append("file").append('\t')
               .append(r.charCount).append('\t')
               .append(r.durationMs).append('\t')
               .append(String.format(java.util.Locale.ROOT, "%.2f", cps)).append('\t')
               .append(r.findings.size()).append('\n');
        }
        Files.writeString(tsvPath, tsv.toString());
        log.info("[CORPUS_BENCH] TSV written: {}", tsvPath.toAbsolutePath());
    }

    // === DTOs (POJOs to keep Jackson happy) ===

    static class FileResult {
        public String category;
        public String fileName;
        public int charCount;
        public long durationMs;
        public List<Finding> findings = List.of();
        public String error;
    }

    static class Finding {
        public String type;
        public double score;
        public String maskedContext;
        public int startPosition;
        public int endPosition;
    }

    static class CategorySummary {
        public int fileCount;
        public int totalChars;
        public int totalFindings;
        public long totalDurationMs;
    }
}
