package pro.softcom.aisentinel.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.*;
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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
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
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.SensitiveData;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compares the precision of two data.sql variants ({@code main/resources/data.sql} vs
 * {@code test/resources/sql/data-improved.sql}) by running the full pipeline (Java backend
 * + Python pii-detector + Postgres) against the hierarchical Confluence corpus under
 * {@code src/test/resources/corpus/}.
 *
 * <p>Corpus structure:
 * <pre>
 *   corpus/
 *     fetch-report.json                   (ignored)
 *     &lt;PII_TYPE_FOLDER&gt;/
 *       &lt;page_folder&gt;/
 *         page.html                       (scanned via HtmlContentParser)
 *         meta.json                       (read for title+url, not scanned)
 *         attachments/
 *           *.pdf|.docx|.xlsx|.msg|...    (scanned via Tika)
 *           *.png|.jpg|.zip|...           (excluded)
 * </pre>
 *
 * <p>The folder name at depth 1 maps to the PII type expected on every page below it, allowing
 * a per-PII-type recall measure ({@link #EXPECTED_PII_TYPES}).
 *
 * <p>Outputs per variant:
 * <pre>
 *   target/corpus-data-sql-comparison/baseline/findings.jsonl
 *   target/corpus-data-sql-comparison/baseline/report.md
 *   target/corpus-data-sql-comparison/improved/findings.jsonl
 *   target/corpus-data-sql-comparison/improved/report.md
 * </pre>
 *
 * <p><strong>Usage</strong> (variable d'env requise pour eviter l'execution involontaire) :
 * <pre>
 *   $env:RUN_CORPUS_DATA_SQL_COMPARISON = "true"
 *   mvn -Dtest=CorpusDataSqlComparisonIT "-Dcorpus.bench.hf-cache=C:\hf-cache" test
 *
 *   # Pour relancer juste une variante :
 *   mvn -Dtest=CorpusDataSqlComparisonIT#runImproved ... test
 * </pre>
 */
@Testcontainers
@SpringBootTest(classes = AiSentinelApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CorpusDataSqlComparisonIT {

    private static final Logger log = LoggerFactory.getLogger(CorpusDataSqlComparisonIT.class);

    private static final String CORPUS_ROOT = "src/test/resources/corpus";
    private static final String OUTPUT_ROOT = "target/corpus-data-sql-comparison";
    private static final int CONTEXT_CHARS = 200;
    private static final int GRPC_PORT = 50051;

    private static final String POSTGRES_ALIAS = "postgres-it";
    private static final String DB_NAME = "ai-sentinel";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";

    /** Folder name at depth 1 -> set of detector PII type codes that satisfy a recall hit. */
    private static final Map<String, Set<String>> EXPECTED_PII_TYPES = Map.of(
        "AVS_NUMBER",                                 Set.of("AVS_NUMBER"),
        "Adresse_MAC",                                Set.of("MAC_ADDRESS"),
        "Carte_de_credit",                            Set.of("CREDIT_CARD", "CREDIT_CARD_NUMBER"),
        "Identifiant_bancaire_international_IBAN",    Set.of("IBAN", "IBAN_CODE"),
        "Identifiant_systeme_ou_compte_de_connexion", Set.of("USERNAME", "PASSWORD", "API_KEY", "SESSION_ID"),
        "MEDICAL_LICENSE",                            Set.of("MEDICAL_LICENSE"),
        "Plaque_d_immatriculation",                   Set.of("LICENSE_PLATE", "VEHICLE_REGISTRATION"),
        "SESSION_ID",                                 Set.of("SESSION_ID"),
        "SOCIALNUM",                                  Set.of("SOCIALNUM"),
        "TAX_ID",                                     Set.of("TAX_ID")
    );

    /** Extensions skipped before any IO — images, archives, opaque binaries. */
    private static final Set<String> EXCLUDED_EXT = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".svg", ".bmp",
        ".zip", ".gz", ".tar", ".7z",
        ".kdbx", ".ipa", ".crt", ".mobileconfig"
    );

    private static final Path HF_CACHE_DIR = Paths.get(
        System.getProperty("corpus.bench.hf-cache",
            System.getProperty("user.home") + "/.ai-sentinel-it-hf-cache"));

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
        // Portee du LLM-as-judge in-pipeline. Le defaut code est {GLINER}, et
        // audit_sources n'est lu QUE depuis cette env var (pas depuis le TOML,
        // cf. note config/detection-settings.toml et llm_validator.py:553-556).
        // runImprovedV3WithOpenMed desactive GLiNER ; sans cet override le judge
        // (active par llm_judge_enabled=true dans data-openmed-no-gliner.sql)
        // tournerait en passthrough total. Inerte pour baseline/improved, qui
        // ont llm_judge_enabled=false : le judge n'y est jamais instancie.
        .withEnv("LLM_JUDGE_AUDIT_SOURCES", "OPENMED,PRESIDIO,REGEX")
        // Stream the Python container logs to a dedicated file so that when the
        // pii-detector process crashes mid-bench (OOM, segfault, unhandled
        // Python exception) we keep its stderr/stdout for post-mortem. Without
        // this, Ryuk removes the container after the JVM detects the failure
        // and the only trace left is the Java-side "analyze failed" log lines.
        //
        // NOTE: we use a custom log consumer instead of the default Slf4jLogConsumer
        // because Python's `logging.StreamHandler()` (and ML libs like httpx,
        // transformers, presidio) write *everything* — INFO included — to stderr.
        // Slf4jLogConsumer routes stderr → ERROR-level slf4j by default, which
        // makes the container output read as a wall of fake ERRORs ("misleading
        // as fuck"). Here we parse the Python log level embedded in each line
        // (" - INFO - ", " - WARNING - ", " - ERROR - ", " - DEBUG - ") and
        // forward to slf4j at the matching level. Lines without an explicit
        // level (e.g. progress bars, raw prints) fall back to INFO.
        .withLogConsumer(CorpusDataSqlComparisonIT::routeContainerLog)
        .waitingFor(Wait.forLogMessage(".*Server started on port.*", 1))
        .withStartupTimeout(Duration.ofMinutes(10));

    private static String ensureHfCacheDir() {
        try {
            Files.createDirectories(HF_CACHE_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create HF cache dir: " + HF_CACHE_DIR, e);
        }
        return HF_CACHE_DIR.toString();
    }

    private static final org.slf4j.Logger CONTAINER_LOG =
        LoggerFactory.getLogger("pii-detector-container");

    /**
     * Forwards a container {@link OutputFrame} to slf4j at the level Python
     * actually used, instead of the Testcontainers default that maps stderr
     * to ERROR. Python writes <i>everything</i> to stderr by default, so the
     * default mapping floods the test log with fake ERRORs.
     */
    private static void routeContainerLog(OutputFrame frame) {
        String raw = frame.getUtf8String();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String line = raw.endsWith("\n") ? raw.substring(0, raw.length() - 1) : raw;
        // Python's standard logging format embeds the level as " - LEVEL - "
        // (e.g. "2026-05-23 09:03:15,599 - presidio-analyzer - INFO - Using device of type: cpu").
        if (line.contains(" - DEBUG - ")) {
            CONTAINER_LOG.debug(line);
        } else if (line.contains(" - WARNING - ") || line.contains(" - WARN - ")) {
            CONTAINER_LOG.warn(line);
        } else if (line.contains(" - ERROR - ") || line.contains(" - CRITICAL - ")) {
            CONTAINER_LOG.error(line);
        } else {
            // INFO is the most common, and any unstructured output (progress
            // bars, raw prints) should not pollute the ERROR channel.
            CONTAINER_LOG.info(line);
        }
    }

    private static ImageFromDockerfile buildPiiDetectorImage() {
        Path repoRoot = Paths.get("..").toAbsolutePath().normalize();
        Path detectorRoot = repoRoot.resolve("pii-detector-service");
        return new ImageFromDockerfile("ai-sentinel-pii-detector-it", false)
            .withFileFromPath("pii-detector-service/Dockerfile",      detectorRoot.resolve("Dockerfile"))
            .withFileFromPath("pii-detector-service/pyproject.toml",  detectorRoot.resolve("pyproject.toml"))
            .withFileFromPath("pii-detector-service/README.md",       detectorRoot.resolve("README.md"))
            .withFileFromPath("pii-detector-service/pii_detector",    detectorRoot.resolve("pii_detector"))
            .withFileFromPath("pii-detector-service/config",          detectorRoot.resolve("config"))
            .withFileFromPath("pii-detector-service/docker-entrypoint.sh",
                detectorRoot.resolve("docker-entrypoint.sh"))
            .withFileFromPath("proto", repoRoot.resolve("proto"))
            .withDockerfile(detectorRoot.resolve("Dockerfile"));
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",                 postgres::getJdbcUrl);
        registry.add("spring.datasource.username",            postgres::getUsername);
        registry.add("spring.datasource.password",            postgres::getPassword);
        registry.add("spring.datasource.driver-class-name",   () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto",         () -> "update");
        registry.add("spring.jpa.show-sql",                   () -> "false");
        registry.add("spring.jpa.properties.hibernate.dialect",
                                                              () -> "org.hibernate.dialect.PostgreSQLDialect");

        registry.add("pii-detector.host",                     piiDetector::getHost);
        registry.add("pii-detector.port",                     () -> piiDetector.getMappedPort(GRPC_PORT));
        // Timeout client gRPC bumpe a 2h pour couvrir le cas extreme du corpus :
        // ISO 20022 for Dummies.pdf (8.5 MB binaire, ~1.6 MB de texte extrait) sur
        // lequel OpenMed avec chunking 1024/256 tokens fait ~500 chunks sequentiels
        // sur CPU. Les Excel volumineux comme Saga-Appareil tournent en ~4 min ;
        // l'objectif ici est de ne pas declencher DEADLINE_EXCEEDED sur le cas pire
        // raisonnable, ce qui est confirme empiriquement par runSingleIso20022Pdf.
        // Le scan complet a observe des req individuelles jusqu'a 4178s (70 min)
        // sur les fichiers >1MB ; 7200s (2h) laisse une marge de securite.
        registry.add("pii-detector.connection-timeout-ms",    () -> "7200000");
        registry.add("pii-detector.request-timeout-ms",       () -> "7200000");

        registry.add("PII_DATABASE_ENCRYPTION_KEY",
            () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("PII_REPORTING_ALLOW_SECRET_REVEAL", () -> "false");
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PiiDetectorClient piiDetectorClient;
    @Autowired private HtmlContentParser htmlContentParser;

    private String parseWithTika(Path file) throws Exception {
        // Bypass {@code Tika.parseToString}'s implicit {@code
        // BodyContentHandler(100_000)} character cap. We deliberately use the
        // unlimited-buffer handler because both OpenMed (128k-token native
        // context, then chunked on the detector side for CPU) and GLiNER
        // (chunked internally) are designed to handle full document bodies;
        // truncation here would silently mask any PII past 100k chars.
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        try (java.io.InputStream stream = java.nio.file.Files.newInputStream(file)) {
            parser.parse(stream, handler, metadata, new ParseContext());
        }
        return handler.toString();
    }
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Smoke test: scanne 1 page.html + 1 attachment d'une seule page pour valider que le pipeline
     * complet (Testcontainers + reseed + Tika/HtmlContentParser + gRPC pii-detector) fonctionne,
     * sans payer le coût des 760+ fichiers du benchmark complet.
     *
     * <p>Le seed utilise {@code data-openmed-no-gliner.sql} (et non le baseline
     * {@code data.sql}) pour que le smoke test exerce le pipeline complet INCLUANT
     * le LLM-as-judge post-filtre : ce variant a {@code llm_judge_enabled=true} et
     * active OPENMED/PRESIDIO/REGEX, ce qui correspond a l'env var
     * {@code LLM_JUDGE_AUDIT_SOURCES=OPENMED,PRESIDIO,REGEX} posee sur le conteneur.
     * Les findings remontes par ces detecteurs sont donc audites par le judge
     * (cf. logs {@code [LLM-JUDGE] post-filter} du conteneur pii-detector).
     *
     * <p>Run isolé :
     * <pre>mvn -Dtest=CorpusDataSqlComparisonIT#smokeSinglePageAndAttachment ... test</pre>
     */
    @Test
    @Order(0)
    void smokeSinglePageAndAttachment() throws Exception {
        log.info("[smoke] === START ===");
        resetAndReseedDb("classpath:sql/data-openmed-no-gliner.sql");

        Path pageDir = Paths.get(CORPUS_ROOT, "AVS_NUMBER", "01_e_Mesam_Scenarii_de_test_OLD_527663382")
                            .toAbsolutePath();
        Path pageHtml = pageDir.resolve("page.html");
        Assertions.assertTrue(Files.isRegularFile(pageHtml),
            "page.html introuvable: " + pageHtml);

        // 1. page.html
        log.info("[smoke] Scanning page.html: {}", pageHtml.getFileName());
        String pageText = extractText(pageHtml);
        Assertions.assertNotNull(pageText, "extractText returned null on page.html");
        Assertions.assertFalse(pageText.isBlank(), "page.html extracted to blank text");
        log.info("[smoke] page.html extracted: {} chars", pageText.length());

        ContentPiiDetection pageDetection = piiDetectorClient.analyzeContent(pageText);
        Assertions.assertNotNull(pageDetection.sensitiveDataFound(),
            "sensitiveDataFound is null");
        log.info("[smoke] page.html -> {} findings", pageDetection.sensitiveDataFound().size());
        pageDetection.sensitiveDataFound().stream().limit(10).forEach(sd ->
            log.info("[smoke]   page : {} [{}] score={} value={}",
                sd.type(), detectorName(sd), sd.score(), truncate(sd.value(), 60)));

        // 2. premier attachment scannable
        Path attachmentsDir = pageDir.resolve("attachments");
        Assertions.assertTrue(Files.isDirectory(attachmentsDir),
            "attachments/ introuvable: " + attachmentsDir);

        Path attachment;
        try (Stream<Path> s = Files.walk(attachmentsDir)) {
            attachment = s.filter(Files::isRegularFile)
                          .filter(CorpusDataSqlComparisonIT::isScannableExtension)
                          .sorted()
                          .findFirst()
                          .orElseThrow(() ->
                              new IllegalStateException("Aucun attachment scannable dans " + attachmentsDir));
        }
        log.info("[smoke] Scanning attachment: {}", attachment.getFileName());

        String attText = extractText(attachment);
        Assertions.assertNotNull(attText,
            "extractText returned null on attachment " + attachment.getFileName());
        Assertions.assertFalse(attText.isBlank(),
            "attachment extracted to blank text: " + attachment.getFileName());
        log.info("[smoke] attachment extracted: {} chars", attText.length());

        ContentPiiDetection attDetection = piiDetectorClient.analyzeContent(attText);
        Assertions.assertNotNull(attDetection.sensitiveDataFound(),
            "attachment sensitiveDataFound is null");
        log.info("[smoke] attachment -> {} findings", attDetection.sensitiveDataFound().size());
        attDetection.sensitiveDataFound().stream().limit(10).forEach(sd ->
            log.info("[smoke]   att  : {} [{}] score={} value={}",
                sd.type(), detectorName(sd), sd.score(), truncate(sd.value(), 60)));

        log.info("[smoke] === DONE ===");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @Test
    @Order(1)
    void runBaseline() throws Exception {
        runVariant("baseline", "classpath:data.sql");
        assertThat(Paths.get(OUTPUT_ROOT, "baseline", "report.md").toAbsolutePath())
            .as("baseline report.md should be written after variant run")
            .exists();
    }

    @Test
    @Order(2)
    void runImproved() throws Exception {
        runVariant("improved", "classpath:sql/data-improved.sql");
        assertThat(Paths.get(OUTPUT_ROOT, "improved", "report.md").toAbsolutePath())
            .as("improved report.md should be written after variant run")
            .exists();
    }

    /**
     * Variante "improved-v3-openmed" : data-improved-2 corrigée selon les verdicts
     * LLM-as-judge du run "improved" + ajout du détecteur OpenMed restreint au
     * complémentaire des autres détecteurs.
     *
     * <p>Sortie : {@code target/corpus-data-sql-comparison/improved-v3-openmed/findings.jsonl}
     * + {@code report.md}. Format JSONL strictement identique aux runs baseline/improved
     * pour être consommable par {@code D:\ai-sentinel-result-eval\scripts\llm_judge_corpus.py}
     * sans modification.
     *
     * <p>Run isolé :
     * <pre>mvn -Dtest=CorpusDataSqlComparisonIT#runImprovedV3WithOpenMed ... test</pre>
     */
    @Test
    @Order(6)
    void runImprovedV3WithOpenMed() throws Exception {
        runVariant("improved-v3-openmed", "classpath:sql/data-openmed-no-gliner.sql");
        assertThat(Paths.get(OUTPUT_ROOT, "improved-v3-openmed", "report.md").toAbsolutePath())
            .as("improved-v3-openmed report.md should be written after variant run")
            .exists();
    }

    /**
     * Test isole sur le fichier qui declenche la cascade UNAVAILABLE dans
     * {@link #runImprovedV3WithOpenMed} : un Excel de 133 KB dont Tika extrait
     * exactement 100 000 chars, ce qui sature le pipeline pii-detector pendant
     * ~226s (GLiNER multipass 212s + Presidio + OpenMed) et apres lequel le
     * container Python devient {@code UNAVAILABLE}, cassant les 372 fichiers
     * suivants.
     *
     * <p>Permet d'iterer rapidement sur le fix de stabilite (cap content, retry
     * client, restart container, healthcheck pre-call) sans relancer le scan
     * complet de 6 minutes.
     *
     * <p>Run isole :
     * <pre>mvn -Dtest=CorpusDataSqlComparisonIT#runSingleSagaAppareilXlsx ... test</pre>
     */
    @Test
    @Order(8)
    void runSingleSagaAppareilXlsx() throws Exception {
        log.info("[single-saga] === START ===");
        resetAndReseedDb("classpath:sql/data-openmed-no-gliner.sql");

        // Warmup: scan a tiny dummy text first to force ALL detectors (incl. OpenMed
        // 2.7 GB model) to load. Without this, the very first analyzeContent call
        // triggers a lazy load mid-request which has been observed to crash the
        // gRPC worker thread on heavy content (cf. the 100k-char Saga-Appareil
        // case below). Separating warmup from the real scan rules out the lazy-load
        // hypothesis: if the real scan still crashes here, the cause is the content,
        // not the load timing.
        Instant tWarm = Instant.now();
        try {
            ContentPiiDetection warm = piiDetectorClient.analyzeContent(
                "Warmup: IBAN CH9300762011623852957 (force detectors to load).");
            log.info("[single-saga] warmup OK in {}s : {} findings",
                Duration.between(tWarm, Instant.now()).toSeconds(),
                warm.sensitiveDataFound().size());
        } catch (Throwable t) {
            log.error("[single-saga] warmup FAILED in {}s : {}",
                Duration.between(tWarm, Instant.now()).toSeconds(), t.toString(), t);
            throw t;
        }

        Path corpusRoot = Paths.get(CORPUS_ROOT).toAbsolutePath();
        String relPath = "Adresse_MAC/01_Informations_complementaires_SAGA_Mobiles_1468268633"
            + "/attachments/Saga-Appareil à jour-07.12.2023.07.33.xlsx";
        Path file = corpusRoot.resolve(relPath.replace('/', java.io.File.separatorChar));
        Assertions.assertTrue(Files.isRegularFile(file),
            "file not found: " + file);

        log.info("[single-saga] file size: {} bytes", Files.size(file));

        Instant tExtract = Instant.now();
        String text = extractText(file);
        long extractSec = Duration.between(tExtract, Instant.now()).toSeconds();
        Assertions.assertNotNull(text, "extractText returned null");
        Assertions.assertFalse(text.isBlank(), "extractText returned blank");
        log.info("[single-saga] extracted text: {} chars in {}s", text.length(), extractSec);

        Instant tAnalyze = Instant.now();
        try {
            ContentPiiDetection detection = piiDetectorClient.analyzeContent(text);
            long analyzeSec = Duration.between(tAnalyze, Instant.now()).toSeconds();
            log.info("[single-saga] SUCCESS in {}s : {} findings",
                analyzeSec, detection.sensitiveDataFound().size());
            Map<String, Long> byType = detection.sensitiveDataFound().stream()
                .collect(Collectors.groupingBy(SensitiveData::type,
                    Collectors.counting()));
            Map<String, Long> byDetector = detection.sensitiveDataFound().stream()
                .collect(Collectors.groupingBy(
                    CorpusDataSqlComparisonIT::detectorName,
                    Collectors.counting()));
            log.info("[single-saga] findings par type     : {}", byType);
            log.info("[single-saga] findings par detector : {}", byDetector);
        } catch (Throwable t) {
            long analyzeSec = Duration.between(tAnalyze, Instant.now()).toSeconds();
            log.error("[single-saga] FAILED after {}s : {}", analyzeSec, t.toString(), t);
            throw t;
        }
        log.info("[single-saga] === DONE ===");
    }

    /**
     * Test isole sur le fichier le plus volumineux du corpus :
     * {@code Identifiant_bancaire_international_IBAN/03_Documents_623509835/attachments/ISO 20022 for Dummies.pdf}
     * (8.5 MB binaire, ~1.6-2 MB de texte apres extraction Tika).
     *
     * <p>Objectif : calibrer le timeout cote client gRPC. Sur les fichiers de
     * cette taille, OpenMed avec chunking 1024/256 tokens fait ~500-700 chunks
     * sequentiels sur CPU, ce qui peut prendre 30-90 min selon la machine. Le
     * timeout actuel de 900s (15 min) declenche {@code DEADLINE_EXCEEDED} et
     * la requete est consideree comme echouee cote client, alors que le
     * container continue a la traiter en background.
     *
     * <p>Ce test permet de mesurer le temps reel d'analyse d'un cas pire
     * raisonnable pour fixer un timeout adequat dans {@code registerProps}.
     *
     * <p>Run isole :
     * <pre>mvn -Dtest=CorpusDataSqlComparisonIT#runSingleIso20022Pdf ... test</pre>
     */
    @Test
    @Order(9)
    void runSingleIso20022Pdf() throws Exception {
        log.info("[single-biggest] === START ===");
        resetAndReseedDb("classpath:sql/data-openmed-no-gliner.sql");

        Instant tWarm = Instant.now();
        ContentPiiDetection warm = piiDetectorClient.analyzeContent(
            "Warmup: IBAN CH9300762011623852957 (force detectors to load).");
        log.info("[single-biggest] warmup OK in {}s : {} findings",
            Duration.between(tWarm, Instant.now()).toSeconds(),
            warm.sensitiveDataFound().size());

        Path corpusRoot = Paths.get(CORPUS_ROOT).toAbsolutePath();
        // Sur ce corpus, les fichiers qui produisent le plus de texte ne sont
        // pas les plus gros en binaire (ISO 20022.pdf = 8.5 MB binaire ne donne
        // que ~95k chars de texte a cause des images/diagrammes). Le pire cas
        // observe pour le contenu textuel pur est cet HTML de scan securite
        // (4.5 MB binaire -> ~1.6 MB de texte extrait, ~400k tokens).
        String relPath = "Identifiant_systeme_ou_compte_de_connexion/01_Securite_675152015"
            + "/attachments/pssec-masters-scan-sla6182t.etat-de-vaud.ch-pod.html";
        Path file = corpusRoot.resolve(relPath.replace('/', java.io.File.separatorChar));
        Assertions.assertTrue(Files.isRegularFile(file),
            "file not found: " + file);

        log.info("[single-biggest] file size: {} bytes", Files.size(file));

        Instant tExtract = Instant.now();
        String text = extractText(file);
        long extractSec = Duration.between(tExtract, Instant.now()).toSeconds();
        Assertions.assertNotNull(text, "extractText returned null");
        Assertions.assertFalse(text.isBlank(), "extractText returned blank");
        log.info("[single-biggest] extracted text: {} chars in {}s", text.length(), extractSec);

        Instant tAnalyze = Instant.now();
        try {
            ContentPiiDetection detection = piiDetectorClient.analyzeContent(text);
            long analyzeSec = Duration.between(tAnalyze, Instant.now()).toSeconds();
            log.info("[single-biggest] SUCCESS in {}s : {} findings",
                analyzeSec, detection.sensitiveDataFound().size());
            log.info("[single-biggest] >>> RECOMMENDED TIMEOUT: {} ms (= {}s analyze * 1.5 safety margin)",
                (long) (analyzeSec * 1500), (long) (analyzeSec * 1.5));
            Map<String, Long> byType = detection.sensitiveDataFound().stream()
                .collect(java.util.stream.Collectors.groupingBy(SensitiveData::type,
                    java.util.stream.Collectors.counting()));
            Map<String, Long> byDetector = detection.sensitiveDataFound().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                    CorpusDataSqlComparisonIT::detectorName,
                    java.util.stream.Collectors.counting()));
            log.info("[single-biggest] findings par type     : {}", byType);
            log.info("[single-biggest] findings par detector : {}", byDetector);
        } catch (Throwable t) {
            long analyzeSec = Duration.between(tAnalyze, Instant.now()).toSeconds();
            log.error("[single-biggest] FAILED after {}s : {}", analyzeSec, t.toString(), t);
            throw t;
        }
        log.info("[single-biggest] === DONE ===");
    }

    /**
     * Texte synthetique couvrant des PII de chacun des types qu'on peut activer
     * dans {@code data-improved-3.sql}. Sert au {@link #smokeAllConfiguredDetectorsProduceFindings}
     * pour verifier qu'aucun detecteur active en config ne reste muet (cas typique :
     * modele OpenMed pas charge dans l'image Docker du pii-detector).
     *
     * <p>Les labels explicites ("Mot de passe :", "Carte de credit :", "IBAN :")
     * aident les detecteurs ML (GLiNER, OpenMed) a accrocher le contexte.
     */
    private static final String SMOKE_TEXT_FOR_ALL_DETECTORS = String.join("\n",
        "Identite et coordonnees du collaborateur",
        "Numero AVS : 756.3047.5009.62",
        "Numero SOCIALNUM : 444-66-8123",
        "Date de naissance : 12.03.1985",
        "",
        "Coordonnees bancaires",
        "IBAN : CH9300762011623852957",
        "BIC : UBSWCHZH80A",
        "Numero de compte bancaire : 12345678901",
        "Account name : Jean Dupont Personal Account",
        "",
        "Cartes de paiement",
        "Carte de credit : 4111 1111 1111 1111",
        "CVV : 123",
        "Code PIN : 4567",
        "",
        "Adresses crypto",
        "Bitcoin : 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa",
        "Ethereum : 0x32Be343B94f860124dC4fEe278FDCBD38C102D88",
        "Litecoin : LZHvBkaJqKJRa8N7Pyb5gJxn5b2gA8U6vR",
        "",
        "Infrastructure",
        "Adresse IP : 192.168.1.42",
        "Adresse MAC : 00:1A:2B:3C:4D:5E",
        "Telephone : +41 21 316 01 57",
        "",
        "Securite et acces",
        "Mot de passe utilisateur : MotDePasseSecret123!",
        "Cle API : sk_live_abc123xyz456def789ghijklmnop",
        "Identifiant client (customer_id) : CUST-987654321",
        "",
        "Identifiants medicaux",
        "Numero de licence medicale : MD-CA-12345",
        "",
        "Vehicules",
        "VIN du vehicule : 1HGBH41JXMN109186",
        "Plaque d'immatriculation : VD-123456",
        "",
        "Telephonie mobile",
        "IMEI : 490154203237518"
    );

    /**
     * Smoke test : reseed la DB avec data-improved-3.sql, puis verifie que CHAQUE
     * detecteur (GLINER/PRESIDIO/REGEX/OPENMED) dont le flag global est true ET qui
     * a au moins un {@code pii_type_config.enabled=true} produit AU MOINS 1 finding
     * sur {@link #SMOKE_TEXT_FOR_ALL_DETECTORS}.
     *
     * <p>Cas typique catche : OpenMed flag {@code true} en DB mais detecteur jamais
     * instancie cote pii-detector (modele HF non telecharge, env var manquante, etc.)
     * — on l'a observe sur le run improved-v3-openmed du 2026-05-22 ou aucun finding
     * OPENMED n'est sorti malgre {@code openmed_enabled=true}.
     *
     * <p>Verification au niveau DETECTEUR uniquement (pas type-par-type) car les
     * modeles ML peuvent rater un type isole sur du texte synthetique court, mais
     * pas tous les types d'un detecteur entier — c'est exactement ce qui revele un
     * detecteur non charge.
     *
     * <p>Run isole :
     * <pre>mvn -Dtest=CorpusDataSqlComparisonIT#smokeAllConfiguredDetectorsProduceFindings ... test</pre>
     */
    @Test
    @Order(7)
    void smokeAllConfiguredDetectorsProduceFindings() throws Exception {
        log.info("[smoke-detectors] === START ===");
        String sqlClasspath = "classpath:sql/data-openmed-no-gliner.sql";
        resetAndReseedDb(sqlClasspath);

        Set<String> expectedDetectors = queryExpectedActiveDetectors();
        log.info("[smoke-detectors] detecteurs attendus selon config : {}", expectedDetectors);
        Assertions.assertFalse(expectedDetectors.isEmpty(),
            "Aucun detecteur actif dans la config — impossible de smoker.");

        ContentPiiDetection detection = piiDetectorClient.analyzeContent(SMOKE_TEXT_FOR_ALL_DETECTORS);
        Assertions.assertNotNull(detection.sensitiveDataFound(),
            "sensitiveDataFound is null");

        Map<String, Long> countByDetector = detection.sensitiveDataFound().stream()
            .collect(java.util.stream.Collectors.groupingBy(
                CorpusDataSqlComparisonIT::detectorName,
                java.util.stream.Collectors.counting()));
        log.info("[smoke-detectors] findings par detecteur : {}", countByDetector);
        detection.sensitiveDataFound().forEach(sd ->
            log.info("[smoke-detectors]   {} [{}] score={} value={}",
                sd.type(), detectorName(sd), sd.score(), truncate(sd.value(), 60)));

        Set<String> missing = new java.util.TreeSet<>(expectedDetectors);
        missing.removeAll(countByDetector.keySet());

        Assertions.assertTrue(missing.isEmpty(),
            "Detecteurs actives en config (flag global + >=1 type enabled) mais sans aucun"
            + " finding sur le smoke text : " + missing
            + ". Findings par detecteur observes : " + countByDetector
            + ". Cause probable : modele non charge dans l'image pii-detector"
            + " (HF cache vide, env var manquante, dependance non installee).");

        log.info("[smoke-detectors] === DONE — tous les detecteurs actifs produisent des findings ===");
    }

    /**
     * Retourne l'intersection des detecteurs (i) dont le flag global est {@code true}
     * dans {@code pii_detection_config} ET (ii) qui ont au moins un type avec
     * {@code pii_type_config.enabled=true}. Ce sont les detecteurs dont on attend
     * concretement des findings.
     */
    private Set<String> queryExpectedActiveDetectors() {
        Boolean glinerOn   = jdbcTemplate.queryForObject(
            "SELECT gliner_enabled   FROM pii_detection_config WHERE id = 1", Boolean.class);
        Boolean presidioOn = jdbcTemplate.queryForObject(
            "SELECT presidio_enabled FROM pii_detection_config WHERE id = 1", Boolean.class);
        Boolean regexOn    = jdbcTemplate.queryForObject(
            "SELECT regex_enabled    FROM pii_detection_config WHERE id = 1", Boolean.class);
        Boolean openmedOn  = jdbcTemplate.queryForObject(
            "SELECT openmed_enabled  FROM pii_detection_config WHERE id = 1", Boolean.class);

        List<String> detectorsWithEnabledTypes = jdbcTemplate.queryForList(
            "SELECT DISTINCT detector FROM pii_type_config WHERE enabled = true", String.class);

        Set<String> result = new java.util.TreeSet<>();
        for (String detector : detectorsWithEnabledTypes) {
            boolean globallyEnabled = switch (detector) {
                case "GLINER"   -> Boolean.TRUE.equals(glinerOn);
                case "PRESIDIO" -> Boolean.TRUE.equals(presidioOn);
                case "REGEX"    -> Boolean.TRUE.equals(regexOn);
                case "OPENMED"  -> Boolean.TRUE.equals(openmedOn);
                default         -> false;
            };
            if (globallyEnabled) {
                result.add(detector);
            }
        }
        return result;
    }

    /**
     * Thread-safe tracker for live progress + ETA during a full-corpus scan.
     * Maintained per variant; nulled out between variants.
     */
    private volatile ProgressTracker progressTracker;

    private static final class ProgressTracker {
        private final long totalCharsToScan;
        private final int totalFiles;
        private final java.util.concurrent.atomic.AtomicLong scannedChars =
            new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong analyzedChars =
            new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong totalAnalyzeMillis =
            new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger doneFiles =
            new java.util.concurrent.atomic.AtomicInteger();

        ProgressTracker(long totalCharsToScan, int totalFiles) {
            this.totalCharsToScan = totalCharsToScan;
            this.totalFiles = totalFiles;
        }

        void recordFile(int chars, long analyzeMillis, boolean successful) {
            scannedChars.addAndGet(chars);
            if (successful) {
                analyzedChars.addAndGet(chars);
                totalAnalyzeMillis.addAndGet(Math.max(0, analyzeMillis));
            }
            doneFiles.incrementAndGet();
        }

        /** Logs an ASCII progress bar with rolling-average velocity and ETA. */
        void logProgress(String variantName, String currentFile) {
            long scanned = scannedChars.get();
            long analyzed = analyzedChars.get();
            long analyzeMs = totalAnalyzeMillis.get();
            int filesDone = doneFiles.get();

            double pct = totalCharsToScan > 0
                ? (100.0 * scanned / totalCharsToScan) : 0.0;
            double velocity = analyzeMs > 0
                ? (analyzed * 1000.0 / analyzeMs) : 0.0;
            long remainingChars = Math.max(0L, totalCharsToScan - scanned);
            long etaSec = velocity > 0 ? (long) (remainingChars / velocity) : -1;

            // 30-char ASCII bar; floor(pct/100 * 30) filled cells.
            int filled = Math.min(30, Math.max(0, (int) (pct / 100.0 * 30)));
            StringBuilder bar = new StringBuilder(32).append('[');
            for (int i = 0; i < 30; i++) bar.append(i < filled ? '#' : '.');
            bar.append(']');

            log.info(
                "[{}] [PROGRESS] {} files {}/{} chars {}/{} ({}%) velocity {} chars/s ETA {} (last: {})",
                variantName, bar,
                filesDone, totalFiles,
                scanned, totalCharsToScan,
                String.format(Locale.ROOT, "%.1f", pct),
                String.format(Locale.ROOT, "%.0f", velocity),
                formatEta(etaSec),
                currentFile);
        }

        private static String formatEta(long etaSec) {
            if (etaSec < 0) return "computing...";
            long h = etaSec / 3600;
            long m = (etaSec % 3600) / 60;
            long s = etaSec % 60;
            if (h > 0) return String.format(Locale.ROOT, "%dh%02dm%02ds", h, m, s);
            if (m > 0) return String.format(Locale.ROOT, "%dm%02ds", m, s);
            return s + "s";
        }
    }

    /**
     * Walk the full corpus once in parallel, extract text with Tika, and sum
     * the character counts to produce a {@link ProgressTracker} pre-loaded
     * with the total work to do. We discard the extracted bodies immediately
     * (only keep the length) so RAM stays bounded — the actual scan pass
     * re-extracts each file. The duplicate Tika cost is paid once and yields
     * an accurate live ETA across the multi-hour scan.
     */
    private ProgressTracker precomputeProgressTracker(String variantName, Path corpusRoot) throws IOException {
        Instant tPre = Instant.now();
        log.info("[{}] [PRE-CALC] walking corpus to compute total char budget...", variantName);

        List<Path> allFiles = new java.util.ArrayList<>();
        for (Path piiTypeDir : listDirectories(corpusRoot)) {
            for (Path pageDir : listDirectories(piiTypeDir)) {
                allFiles.addAll(collectScannableFiles(pageDir));
            }
        }
        log.info("[{}] [PRE-CALC] {} files to evaluate", variantName, allFiles.size());

        java.util.concurrent.atomic.AtomicLong totalChars = new java.util.concurrent.atomic.AtomicLong();
        allFiles.parallelStream().forEach(file -> {
            try {
                String t = extractText(file);
                if (t != null) totalChars.addAndGet(t.length());
            } catch (Throwable _) {
                // Unreadable files are skipped both in pre-calc and scan,
                // so they don't bias the ETA.
            }
        });

        long total = totalChars.get();
        long preSec = Duration.between(tPre, Instant.now()).toSeconds();
        log.info("[{}] [PRE-CALC] total {} chars across {} files (pre-calc {}s)",
            variantName, total, allFiles.size(), preSec);
        return new ProgressTracker(total, allFiles.size());
    }

    private void runVariant(String variantName, String sqlClasspath) throws Exception {
        log.info("[{}] === START variant {} from {} ===", variantName, variantName, sqlClasspath);
        Instant start = Instant.now();

        resetAndReseedDb(sqlClasspath);
        log.info("[{}] DB reset+reseed done", variantName);

        Path corpusRoot = Paths.get(CORPUS_ROOT).toAbsolutePath();
        Path outDir = Paths.get(OUTPUT_ROOT, variantName).toAbsolutePath();
        Files.createDirectories(outDir);

        Stats stats = new Stats();
        Path findingsPath = outDir.resolve("findings.jsonl");
        Path processedPath = outDir.resolve("processed.txt");

        // Resume support: a run can be cut short by the pii-detector container
        // dying under load (the "UNAVAILABLE cascade" — a single monster file
        // saturates the in-pipeline LLM judge, the worker crashes, and every
        // subsequent file fails). On the next launch we DON'T redo the work that
        // already succeeded: {@code findings.jsonl} (every file that produced >=1
        // finding) and the {@code processed.txt} sidecar (every file fully
        // handled, including 0-finding and blank ones) define the skip-set, and
        // the prior findings are pre-loaded into {@code stats} so the regenerated
        // report stays correct after a resume. See loadPriorRun / ResumeState.
        Set<String> doneFiles = new HashSet<>();
        Map<String, int[]> priorPageCounts = new HashMap<>();
        boolean resuming = Files.exists(processedPath)
            || (Files.exists(findingsPath) && Files.size(findingsPath) > 0);
        loadPriorRun(variantName, findingsPath, processedPath, stats, doneFiles, priorPageCounts);

        // Pre-extract total char count to drive a meaningful progress bar.
        // We do this in parallel and only sum lengths (no caching of bodies)
        // so RAM stays bounded. Cost: a few minutes of Tika extraction, paid
        // upfront in exchange for a real ETA during the multi-hour scan.
        ProgressTracker progress = precomputeProgressTracker(variantName, corpusRoot);
        this.progressTracker = progress;

        // Append (not truncate) when resuming so prior findings are preserved.
        OpenOption[] findingsOpts = resuming
            ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE}
            : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};

        try (BufferedWriter findingsWriter = Files.newBufferedWriter(findingsPath, StandardCharsets.UTF_8, findingsOpts);
             BufferedWriter processedWriter = Files.newBufferedWriter(processedPath, StandardCharsets.UTF_8,
                 StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {

            ResumeState resume = new ResumeState(doneFiles, priorPageCounts, processedWriter);
            log.info("[{}] RESUME mode={}: {} files already done (skipped), {} prior findings pre-loaded",
                variantName, resuming, doneFiles.size(), stats.totalFindings);

            List<Path> piiTypeDirs = listDirectories(corpusRoot);
            log.info("[{}] {} PII type folders to scan", variantName, piiTypeDirs.size());

            for (Path piiTypeDir : piiTypeDirs) {
                String piiTypeFolder = piiTypeDir.getFileName().toString();
                Set<String> expectedTypes = EXPECTED_PII_TYPES.getOrDefault(piiTypeFolder, Set.of());

                List<Path> pageDirs = listDirectories(piiTypeDir);
                log.info("[{}] [{}] {} pages", variantName, piiTypeFolder, pageDirs.size());

                for (Path pageDir : pageDirs) {
                    scanPage(variantName, corpusRoot, piiTypeFolder, expectedTypes, pageDir, stats,
                             findingsWriter, resume);
                }
            }
        } finally {
            this.progressTracker = null;
        }

        Path reportPath = outDir.resolve("report.md");
        writeReport(reportPath, variantName, sqlClasspath, stats, Duration.between(start, Instant.now()));

        log.info("[{}] === DONE === findings={} files={} duration={}s -> {}",
            variantName, stats.totalFindings, stats.filesScanned,
            Duration.between(start, Instant.now()).toSeconds(), outDir);
    }

    /**
     * State carried through a resumable run.
     *
     * @param doneFiles       corpus-relative paths already handled in a previous
     *                        run (success OR blank) — skipped on this pass.
     * @param priorPageCounts {@code "piiTypeFolder/pageFolder" -> [expectedFindings, otherFindings]}
     *                        pre-loaded from the existing findings.jsonl so the
     *                        per-page / recall stats stay correct after resume.
     * @param processedWriter append-only sidecar; each handled file is flushed
     *                        immediately so a hard kill mid-run still checkpoints.
     */
    private record ResumeState(Set<String> doneFiles, Map<String, int[]> priorPageCounts,
                               BufferedWriter processedWriter) {

        void markProcessed(String relPath) throws IOException {
            if (doneFiles.add(relPath)) {
                processedWriter.write(relPath);
                processedWriter.newLine();
                processedWriter.flush();
            }
        }
    }

    /**
     * Pre-loads the skip-set and aggregate stats from a previous run's outputs so
     * a resumed run continues "where the first errors were" without re-scanning
     * what already succeeded nor duplicating findings.
     *
     * <p>Skip-set sources:
     * <ul>
     *   <li>{@code findings.jsonl} — any file with >=1 finding is done; its
     *       findings are folded back into {@code stats} (totals, by-type,
     *       by-detector, by-file, per-page expected/other) so the final report
     *       reflects the full corpus, not just the resumed slice.</li>
     *   <li>{@code processed.txt} — files fully handled with 0 findings or blank
     *       extraction (not recoverable from findings.jsonl alone).</li>
     * </ul>
     *
     * <p>Files that previously FAILED appear in neither source, so they are
     * naturally retried on resume.
     */
    private void loadPriorRun(String variantName, Path findingsPath, Path processedPath, Stats stats,
                              Set<String> doneFiles, Map<String, int[]> priorPageCounts) throws IOException {
        if (Files.exists(processedPath)) {
            for (String line : Files.readAllLines(processedPath, StandardCharsets.UTF_8)) {
                String p = line.strip();
                if (!p.isEmpty()) {
                    doneFiles.add(p);
                }
            }
        }
        if (!Files.exists(findingsPath)) {
            return;
        }
        Set<String> filesWithFindings = new HashSet<>();
        for (String line : Files.readAllLines(findingsPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = MAPPER.readTree(line);
            String relPath = textOrNull(n, "relativePath");
            String type = textOrNull(n, "piiTypeDetected");
            String detector = textOrNull(n, "detector");
            String folder = textOrNull(n, "piiTypeFolder");
            String page = textOrNull(n, "pageFolder");
            boolean expectedHit = n.path("expectedHit").asBoolean(false);

            if (relPath != null) {
                doneFiles.add(relPath);
                filesWithFindings.add(relPath);
                stats.countByFile.merge(relPath, 1, Integer::sum);
            }
            stats.totalFindings++;
            if (type != null) {
                stats.countByPiiType.merge(type, 1, Integer::sum);
            }
            if (detector != null) {
                stats.countByDetector.merge(detector, 1, Integer::sum);
            }
            if (folder != null && page != null) {
                int[] counts = priorPageCounts.computeIfAbsent(folder + "/" + page, k -> new int[2]);
                counts[expectedHit ? 0 : 1]++;
            }
        }
        stats.filesScanned += filesWithFindings.size();
        log.info("[{}] loadPriorRun: {} prior findings across {} files, {} files in skip-set",
            variantName, stats.totalFindings, filesWithFindings.size(), doneFiles.size());
    }

    private void scanPage(String variantName, Path corpusRoot, String piiTypeFolder, Set<String> expectedTypes,
                          Path pageDir, Stats stats, BufferedWriter findingsWriter, ResumeState resume)
            throws IOException {
        PageMeta meta = readMeta(pageDir.resolve("meta.json"));
        PageStats pageStats = new PageStats(piiTypeFolder, pageDir.getFileName().toString(), meta);
        // Fold this page's prior-run findings back in so recall / per-page stats
        // stay correct when resuming (those files are skipped below, so they
        // won't be re-counted from the live scan).
        int[] prior = resume.priorPageCounts().get(piiTypeFolder + "/" + pageDir.getFileName());
        if (prior != null) {
            pageStats.expectedFindings += prior[0];
            pageStats.otherFindings += prior[1];
        }

        List<Path> filesToScan = collectScannableFiles(pageDir);

        for (Path file : filesToScan) {
            String relPath = corpusRoot.relativize(file).toString().replace('\\', '/');
            if (resume.doneFiles().contains(relPath)) {
                continue; // already handled in a previous run — resume skip
            }
            boolean isAttachment = file.getParent().getFileName().toString().equals("attachments")
                || (file.getParent().getParent() != null
                    && "attachments".equals(file.getParent().getParent().getFileName().toString()));

            String text = extractText(file);
            if (text == null || text.isBlank()) {
                stats.skippedFiles.add(relPath);
                resume.markProcessed(relPath); // blank is deterministic — don't retry on resume
                continue;
            }

            Instant tAnalyze = Instant.now();
            ContentPiiDetection detection = analyzeWithRetry(variantName, relPath, text);
            long analyzeMillis = Duration.between(tAnalyze, Instant.now()).toMillis();
            if (detection == null) {
                stats.failedFiles.add(relPath);
                if (progressTracker != null) {
                    progressTracker.recordFile(text.length(), analyzeMillis, false);
                    progressTracker.logProgress(variantName, relPath);
                }
                // A failure may mean the pii-detector container died (the
                // "UNAVAILABLE cascade"). Probe it and restart in place if it is
                // unreachable, so the NEXT file does not fail for the same
                // reason. The failed file is intentionally NOT checkpointed, so a
                // later resume retries it. See ensureDetectorHealthy.
                ensureDetectorHealthy(variantName);
                continue;
            }

            stats.filesScanned++;
            if (progressTracker != null) {
                progressTracker.recordFile(text.length(), analyzeMillis, true);
                progressTracker.logProgress(variantName, relPath);
            }

            for (SensitiveData sd : detection.sensitiveDataFound()) {
                boolean expectedHit = expectedTypes.contains(sd.type());
                writeFindingLine(findingsWriter, piiTypeFolder, pageDir.getFileName().toString(),
                                 meta, relPath, isAttachment, text, sd, expectedHit);
                stats.totalFindings++;
                stats.countByPiiType.merge(sd.type(), 1, Integer::sum);
                stats.countByDetector.merge(detectorName(sd), 1, Integer::sum);
                stats.countByFile.merge(relPath, 1, Integer::sum);
                pageStats.recordFinding(sd, expectedHit);
            }
            findingsWriter.flush(); // durable per-file so a kill mid-run keeps results
            resume.markProcessed(relPath);
        }

        stats.pages.add(pageStats);
        RecallCounter rc = stats.recallByPiiType.computeIfAbsent(piiTypeFolder, k -> new RecallCounter());
        rc.totalPages++;
        if (pageStats.expectedFindings > 0) {
            rc.hitPages++;
        }
    }

    /**
     * Verifies the pii-detector container still answers; if it does not,
     * restarts it in place. Called after a failed analyze so a dead container
     * (OOM / crashed gRPC worker after a finding-dense "monster" file) cannot
     * cascade into every remaining file being marked as failed.
     *
     * <p>A cheap warmup-sized probe distinguishes the two failure modes:
     * <ul>
     *   <li>probe succeeds → the container is alive; the file itself was the
     *       problem (too large / DEADLINE_EXCEEDED). Nothing to do.</li>
     *   <li>probe fails → the container is unreachable; restart it.</li>
     * </ul>
     */
    private void ensureDetectorHealthy(String variantName) {
        try {
            piiDetectorClient.analyzeContent("health-probe IBAN CH9300762011623852957");
            return;
        } catch (Throwable probeErr) {
            log.warn("[{}][recovery] health probe failed ({}); restarting pii-detector to avoid cascade",
                variantName, probeErr.toString());
        }
        restartPiiDetector(variantName);
    }

    /**
     * Restarts the pii-detector container in place via {@code docker restart}.
     *
     * <p>We use restart (not stop+start) on purpose: Docker preserves the
     * published host port across a restart, so the mapped gRPC port — captured
     * once by Spring at context init via {@link #registerProps} — stays valid and
     * the autowired {@link PiiDetectorClient} reconnects transparently. A
     * stop+start would allocate a NEW host port and strand the client.
     */
    private void restartPiiDetector(String variantName) {
        String containerId = piiDetector.getContainerId();
        int portBefore = piiDetector.getMappedPort(GRPC_PORT);
        Instant t0 = Instant.now();
        log.warn("[{}][recovery] restarting pii-detector container {}", variantName, containerId);
        try {
            DockerClientFactory.instance().client()
                .restartContainerCmd(containerId)
                .withTimeout(30) // seconds for graceful stop before kill
                .exec();
        } catch (Throwable t) {
            log.error("[{}][recovery] docker restart command failed: {} — will still wait for readiness",
                variantName, t.toString(), t);
        }
        waitForDetectorReady(variantName);
        int portAfter = piiDetector.getMappedPort(GRPC_PORT);
        if (portBefore != portAfter) {
            log.error("[{}][recovery] mapped gRPC port changed after restart ({} -> {}); "
                + "the autowired client is now stale", variantName, portBefore, portAfter);
        }
        log.warn("[{}][recovery] pii-detector restarted in {}s (port {})",
            variantName, Duration.between(t0, Instant.now()).toSeconds(), portAfter);
    }

    /**
     * Polls the restarted container with a tiny warmup analyze until it serves
     * again (models reload on boot) or a 15-minute deadline elapses. Each probe
     * also re-establishes the gRPC channel and forces model load, so the next
     * real file is served by a fully-ready detector.
     */
    private void waitForDetectorReady(String variantName) {
        try {
            Awaitility.await()
                .atMost(Duration.ofMinutes(15))
                .pollInterval(Duration.ofSeconds(5))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    piiDetectorClient.analyzeContent("warmup IBAN CH9300762011623852957");
                    log.info("[{}][recovery] pii-detector ready", variantName);
                });
        } catch (ConditionTimeoutException e) {
            log.error("[{}][recovery] pii-detector NOT ready within 15min after restart", variantName);
        }
    }

    /**
     * Wrap {@link PiiDetectorClient#analyzeContent} with a small retry on transient
     * gRPC errors. Without this, a single transient {@code UNAVAILABLE} (e.g. the
     * pii-detector container momentarily unreachable due to network hiccup or
     * service restart) immediately marked the file as failed, and the cascading
     * failures observed in the run pre-Armeria-fix would propagate to all 372
     * subsequent files.
     *
     * <p>Strategy: 3 attempts, exponential backoff (5s, 15s). Stops fast on
     * non-retryable errors ({@code INVALID_ARGUMENT}, {@code Content too large})
     * since those will never succeed regardless of retry.
     *
     * @return the detection result, or {@code null} if all attempts failed
     */
    private ContentPiiDetection analyzeWithRetry(String variantName, String relPath, String text) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return piiDetectorClient.analyzeContent(text);
            } catch (Throwable t) {
                String msg = t.toString();
                if (msg.contains("INVALID_ARGUMENT") || msg.contains("Content too large")) {
                    log.warn("[{}] non-retryable analyze failure for {}: {}",
                        variantName, relPath, msg);
                    return null;
                }
                if (attempt == maxAttempts) {
                    log.warn("[{}] analyze failed for {} after {} attempts: {}",
                        variantName, relPath, maxAttempts, msg);
                    return null;
                }
                long backoffMs = 5_000L * attempt;
                log.warn("[{}] analyze attempt {}/{} failed for {} : {} — retrying in {}ms",
                    variantName, attempt, maxAttempts, relPath, msg, backoffMs);
                LockSupport.parkNanos(backoffMs * 1_000_000L);
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private static List<Path> listDirectories(Path parent) throws IOException {
        try (Stream<Path> s = Files.list(parent)) {
            return s.filter(Files::isDirectory).sorted().toList();
        }
    }

    private static List<Path> collectScannableFiles(Path pageDir) throws IOException {
        List<Path> result = new ArrayList<>();
        Path pageHtml = pageDir.resolve("page.html");
        if (Files.isRegularFile(pageHtml)) {
            result.add(pageHtml);
        }
        Path attachmentsDir = pageDir.resolve("attachments");
        if (Files.isDirectory(attachmentsDir)) {
            try (Stream<Path> s = Files.walk(attachmentsDir)) {
                s.filter(Files::isRegularFile)
                 .filter(CorpusDataSqlComparisonIT::isScannableExtension)
                 .sorted()
                 .forEach(result::add);
            }
        }
        return result;
    }

    private static boolean isScannableExtension(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return true;
        }
        return !EXCLUDED_EXT.contains(name.substring(dot));
    }

    private void resetAndReseedDb(String sqlClasspath) throws SQLException {
        jdbcTemplate.execute("DELETE FROM pii_type_config");
        jdbcTemplate.execute("DELETE FROM pii_detection_config");

        Resource resource = new DefaultResourceLoader().getResource(sqlClasspath);
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new EncodedResource(resource, StandardCharsets.UTF_8));
        }
    }

    private String extractText(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            String raw;
            if (name.endsWith(".html") || name.endsWith(".htm")) {
                raw = htmlContentParser.cleanText(Files.readString(file, StandardCharsets.UTF_8));
            } else {
                raw = parseWithTika(file);
            }
            return raw;
        } catch (Throwable t) {
            // Catch Throwable (not Exception) so a NoClassDefFoundError raised by Tika sub-parsers
            // (e.g. OutlookExtractor → jsoup TagSet on .msg files) doesn't abort the whole run.
            log.debug("extractText failed for {}: {}", file, t.toString());
            return null;
        }
    }

    private PageMeta readMeta(Path metaJson) {
        if (!Files.isRegularFile(metaJson)) {
            return PageMeta.EMPTY;
        }
        try {
            JsonNode n = MAPPER.readTree(metaJson.toFile());
            return new PageMeta(textOrNull(n, "title"), textOrNull(n, "url"), textOrNull(n, "pageId"));
        } catch (IOException e) {
            log.debug("readMeta failed for {}: {}", metaJson, e.toString());
            return PageMeta.EMPTY;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private void writeFindingLine(BufferedWriter writer,
                                  String piiTypeFolder, String pageFolder, PageMeta meta,
                                  String relPath, boolean isAttachment, String fullText,
                                  SensitiveData sd, boolean expectedHit) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("piiTypeFolder", piiTypeFolder);
        node.put("pageFolder", pageFolder);
        node.put("pageTitle", meta.title);
        node.put("pageUrl",   meta.url);
        node.put("pageId",    meta.pageId);
        node.put("relativePath", relPath);
        node.put("isAttachment", isAttachment);
        node.put("piiTypeDetected", sd.type());
        node.put("typeLabel", sd.typeLabel());
        node.put("detector", detectorName(sd));
        if (sd.score() != null) {
            node.put("score", sd.score());
        } else {
            node.putNull("score");
        }
        node.put("value", sd.value());
        node.put("contextBefore", contextBefore(fullText, sd.position()));
        node.put("contextAfter",  contextAfter(fullText, sd.end()));
        node.put("start", sd.position());
        node.put("end", sd.end());
        node.put("expectedHit", expectedHit);
        writer.write(MAPPER.writeValueAsString(node));
        writer.newLine();
    }

    private static String contextBefore(String text, int start) {
        int from = Math.max(0, start - CONTEXT_CHARS);
        int to = Math.max(0, Math.min(start, text.length()));
        return text.substring(from, to).replace("\n", " ").replace("\r", " ").trim();
    }

    private static String contextAfter(String text, int end) {
        int from = Math.max(0, Math.min(end, text.length()));
        int to = Math.min(text.length(), end + CONTEXT_CHARS);
        return text.substring(from, to).replace("\n", " ").replace("\r", " ").trim();
    }

    private static String detectorName(SensitiveData sd) {
        return sd.source() != null ? sd.source().name() : "UNKNOWN";
    }

    private static void writeReport(Path path, String variant, String sql, Stats stats, Duration dur)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Corpus Data SQL Comparison Report — variant: ").append(variant).append("\n\n");

        sb.append("## Run metadata\n\n");
        sb.append("- Variant: `").append(variant).append("`\n");
        sb.append("- SQL source: `").append(sql).append("`\n");
        sb.append("- Run timestamp: ").append(Instant.now()).append("\n");
        sb.append("- Duration: ").append(dur.toSeconds()).append("s\n");
        sb.append("- Files scanned: ").append(stats.filesScanned).append("\n");
        sb.append("- Files skipped (unreadable/empty): ").append(stats.skippedFiles.size()).append("\n");
        sb.append("- Files failed (analyze error): ").append(stats.failedFiles.size()).append("\n");
        sb.append("- Pages scanned: ").append(stats.pages.size()).append("\n");
        sb.append("- **Total findings: ").append(stats.totalFindings).append("**\n\n");

        appendRecallSection(sb, stats);
        appendFindingsByPiiTypeSection(sb, stats);
        appendFindingsByDetectorSection(sb, stats);
        appendPagesSection(sb, stats);
        appendTopFilesSection(sb, stats);
        appendSkippedFailedSections(sb, stats);

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void appendRecallSection(StringBuilder sb, Stats stats) {
        sb.append("## Recall par PII type attendu\n\n");
        sb.append("| PII type folder | Pages | Pages hit | Recall % | Codes acceptés |\n");
        sb.append("|---|---:|---:|---:|---|\n");
        EXPECTED_PII_TYPES.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                String folder = entry.getKey();
                RecallCounter rc = stats.recallByPiiType.getOrDefault(folder, new RecallCounter());
                sb.append("| ").append(folder)
                  .append(" | ").append(rc.totalPages)
                  .append(" | ").append(rc.hitPages)
                  .append(" | ").append(formatPct(rc.hitPages, rc.totalPages))
                  .append(" | ").append(String.join(", ", entry.getValue()))
                  .append(" |\n");
            });
        sb.append("\n");
    }

    private static void appendFindingsByPiiTypeSection(StringBuilder sb, Stats stats) {
        sb.append("## Findings par PII type détecté\n\n| PII Type | Count | % total |\n|---|---:|---:|\n");
        stats.countByPiiType.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(e -> sb.append("| ").append(e.getKey())
                .append(" | ").append(e.getValue())
                .append(" | ").append(formatPct(e.getValue(), stats.totalFindings))
                .append(" |\n"));
        sb.append("\n");
    }

    private static void appendFindingsByDetectorSection(StringBuilder sb, Stats stats) {
        sb.append("## Findings par détecteur\n\n| Detector | Count |\n|---|---:|\n");
        stats.countByDetector.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(e -> sb.append("| ").append(e.getKey())
                .append(" | ").append(e.getValue()).append(" |\n"));
        sb.append("\n");
    }

    private static void appendPagesSection(StringBuilder sb, Stats stats) {
        sb.append("## Pages Confluence (").append(stats.pages.size()).append(")\n\n");
        sb.append("| PII attendu | Page | Expected | Other | Total | Confluence URL |\n");
        sb.append("|---|---|---:|---:|---:|---|\n");
        stats.pages.stream()
            .sorted(Comparator.comparing((PageStats p) -> p.piiTypeFolder).thenComparing(p -> p.pageFolder))
            .forEach(p -> {
                String title = (p.meta.title != null && !p.meta.title.isBlank()) ? p.meta.title : p.pageFolder;
                String urlCell = (p.meta.url != null && !p.meta.url.isBlank())
                    ? "[link](" + p.meta.url + ")" : "";
                sb.append("| ").append(p.piiTypeFolder)
                  .append(" | ").append(escapeMdCell(title))
                  .append(" | ").append(p.expectedFindings)
                  .append(" | ").append(p.otherFindings)
                  .append(" | ").append(p.expectedFindings + p.otherFindings)
                  .append(" | ").append(urlCell)
                  .append(" |\n");
            });
        sb.append("\n");
    }

    private static void appendTopFilesSection(StringBuilder sb, Stats stats) {
        sb.append("## Top 30 fichiers par nombre de findings\n\n| File | Total |\n|---|---:|\n");
        stats.countByFile.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(30)
            .forEach(e -> sb.append("| ").append(e.getKey())
                .append(" | ").append(e.getValue()).append(" |\n"));
        sb.append("\n");
    }

    private static void appendSkippedFailedSections(StringBuilder sb, Stats stats) {
        if (!stats.failedFiles.isEmpty()) {
            sb.append("## Files failed (").append(stats.failedFiles.size()).append(")\n\n");
            stats.failedFiles.forEach(f -> sb.append("- ").append(f).append("\n"));
            sb.append("\n");
        }
        if (!stats.skippedFiles.isEmpty()) {
            sb.append("## Files skipped — unreadable or empty after extraction (")
              .append(stats.skippedFiles.size()).append(")\n\n");
            stats.skippedFiles.forEach(f -> sb.append("- ").append(f).append("\n"));
        }
    }

    private static String escapeMdCell(String s) {
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }

    private static String formatPct(int count, int total) {
        if (total == 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * count / total);
    }

    private record PageMeta(String title, String url, String pageId) {
        static final PageMeta EMPTY = new PageMeta(null, null, null);
    }

    private static final class PageStats {
        final String piiTypeFolder;
        final String pageFolder;
        final PageMeta meta;
        final Set<String> types = new HashSet<>();
        int expectedFindings = 0;
        int otherFindings = 0;

        PageStats(String piiTypeFolder, String pageFolder, PageMeta meta) {
            this.piiTypeFolder = piiTypeFolder;
            this.pageFolder = pageFolder;
            this.meta = meta;
        }

        void recordFinding(SensitiveData sd, boolean expectedHit) {
            types.add(sd.type());
            if (expectedHit) {
                expectedFindings++;
            } else {
                otherFindings++;
            }
        }
    }

    private static final class RecallCounter {
        int totalPages = 0;
        int hitPages = 0;
    }

    /** Aggregated counters built during a variant run. */
    private static final class Stats {
        int filesScanned = 0;
        int totalFindings = 0;
        final Map<String, Integer> countByPiiType  = new TreeMap<>();
        final Map<String, Integer> countByDetector = new TreeMap<>();
        final Map<String, Integer> countByFile     = new LinkedHashMap<>();
        final Map<String, RecallCounter> recallByPiiType = new TreeMap<>();
        final List<PageStats> pages = new ArrayList<>();
        final List<String> skippedFiles = new ArrayList<>();
        final List<String> failedFiles  = new ArrayList<>();
    }
}
