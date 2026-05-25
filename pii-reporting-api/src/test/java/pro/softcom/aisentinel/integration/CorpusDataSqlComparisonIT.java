package pro.softcom.aisentinel.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
//@EnabledIfEnvironmentVariable(named = "RUN_CORPUS_DATA_SQL_COMPARISON", matches = "true")
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

    /**
     * Fichiers qui ont failed dans le run "baseline" (gRPC piiDetectorClient throw).
     * Liste extraite de la section "Files failed" du report.md baseline.
     * Tous ces 11 paths apparaissent aussi dans les 24 failed d'improved : ils peuvent
     * etre durablement cassés ou transitoires. Le retry tente 3 fois avec backoff.
     * Cf. methode {@link #retryBaselineFailedFiles()}.
     */
    private static final List<String> BASELINE_FAILED_PATHS = List.of(
        "AVS_NUMBER/04_UNIREG_1757151495/attachments/SCOPE - Schéma fonctionnel",
        "Identifiant_systeme_ou_compte_de_connexion/01_Securite_675152015/attachments/pssec-masters-scan-sla6181t.etat-de-vaud.ch-pod.html",
        "Identifiant_systeme_ou_compte_de_connexion/01_Securite_675152015/attachments/pssec-masters-scan-sla6182t.etat-de-vaud.ch-pod.html",
        "Identifiant_systeme_ou_compte_de_connexion/01_Securite_675152015/attachments/pssec-masters-scan-sla6183t.etat-de-vaud.ch-pod.html",
        "Identifiant_systeme_ou_compte_de_connexion/01_Securite_675152015/attachments/pssec-workers-scan-sla6190t.etat-de-vaud.ch-pod.html",
        "Identifiant_systeme_ou_compte_de_connexion/01_Securite_675152015/attachments/pssec-workers-scan-sla6191t.etat-de-vaud.ch-pod.html",
        "MEDICAL_LICENSE/05_ECM_KBACI_Fiche_Sanity_check_ACS_7_2_1476461456/attachments/documents_associations_val_oracle.csv",
        "MEDICAL_LICENSE/05_ECM_KBACI_Fiche_Sanity_check_ACS_7_2_1476461456/attachments/documents_associations_val_postgres.csv",
        "MEDICAL_LICENSE/05_ECM_KBACI_Fiche_Sanity_check_ACS_7_2_1476461456/attachments/oracle.csv",
        "Plaque_d_immatriculation/02_Subside_RIE3_Specifications_558891070/attachments/Subside RIE3 - vue globale",
        "SOCIALNUM/04_BO_2_En_tant_que_collaborateur_DGS_j_aimerais_pouvoir_importer_le_fichier_Excel_SID_genere_depuis_une_interface_web_de_l_1077313875/attachments/SID_20201019_20201102.xlsx"
    );

    /**
     * Fichiers qui ont failed dans le run "improved" mais qui sont passes dans "baseline".
     * Liste extraite par diff des sections "Files failed" des 2 rapports.
     * Ces echecs sont transitoires (timeout/OOM gRPC du pii-detector), pas Tika :
     * un retry cible permet de les recuperer sans relancer les 6h du run complet.
     * Cf. methode {@link #retryImprovedFailedFiles()}.
     */
    private static final List<String> IMPROVED_ONLY_FAILED_PATHS = List.of(
        "MEDICAL_LICENSE/05_ECM_KBACI_Fiche_Sanity_check_ACS_7_2_1476461456/attachments/postgres.csv",
        "Plaque_d_immatriculation/02_Subside_RIE3_Specifications_558891070/attachments/Calculateur 2023_v1.xlsx",
        "Plaque_d_immatriculation/02_Subside_RIE3_Specifications_558891070/attachments/Calculateur 2025.xlsx",
        "Plaque_d_immatriculation/02_Subside_RIE3_Specifications_558891070/attachments/Copie de Calculateur 2020_V3.xlsx",
        "Plaque_d_immatriculation/02_Subside_RIE3_Specifications_558891070/attachments/Copie de Calculateur 2021_V5.xlsx",
        "Plaque_d_immatriculation/02_Subside_RIE3_Specifications_558891070/attachments/Processus demande de subside RIE3",
        "Plaque_d_immatriculation/03_e_Mesam_Scenarii_de_test_OLD_527663382/attachments/liste_demandes_traites.xls",
        "SESSION_ID/05_FTX_Traces_InterSystem_534904966/attachments/ZVDF_07.Log__PROD__SIF__SAVI.txt",
        "SESSION_ID/05_FTX_Traces_InterSystem_534904966/attachments/ZVDF_09.Log__PROD__SIF__SAVI.txt",
        "SOCIALNUM/01_Informations_complementaires_SAGA_Mobiles_1468268633/attachments/Saga-Appareil à jour-07.12.2023.07.33.xlsx",
        "SOCIALNUM/02_CAMT053_Procedure_et_traitements_des_erreurs_1743650892/attachments/Améliorations_EBS_v2.1.docx",
        "SOCIALNUM/02_CAMT053_Procedure_et_traitements_des_erreurs_1743650892/attachments/Tableau des règles de comptabilisation des encaissements - V2.xlsx",
        "TAX_ID/01_1_2_En_tant_que_contribuable_je_veux_avoir_acces_a_la_liste_des_themes_sous_themes_a_declarer_et_a_la_liste_des_outils_a_1685258840/page.html"
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
        .withFileSystemBind(ensureHfCacheDir(), "/app/.cache/huggingface")
        .withEnv("HF_HOME", "/app/.cache/huggingface")
        .withEnv("TRANSFORMERS_CACHE", "/app/.cache/huggingface")
        .withEnv("DB_HOST", POSTGRES_ALIAS)
        .withEnv("DB_PORT", "5432")
        .withEnv("DB_NAME", DB_NAME)
        .withEnv("DB_USER", DB_USER)
        .withEnv("DB_PASSWORD", DB_PASSWORD)
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
            .withDockerfilePath("pii-detector-service/Dockerfile");
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

    private static final Tika TIKA = new Tika();

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
     * <p>Run isolé :
     * <pre>mvn -Dtest=CorpusDataSqlComparisonIT#smokeSinglePageAndAttachment ... test</pre>
     */
    @Test
    @Order(0)
    void smokeSinglePageAndAttachment() throws Exception {
        log.info("[smoke] === START ===");
        resetAndReseedDb("classpath:data.sql");

        Path pageDir = Paths.get(CORPUS_ROOT, "AVS_NUMBER", "01_e_Mesam_Scenarii_de_test_OLD_527663382")
                            .toAbsolutePath();
        Path pageHtml = pageDir.resolve("page.html");
        org.junit.jupiter.api.Assertions.assertTrue(Files.isRegularFile(pageHtml),
            "page.html introuvable: " + pageHtml);

        // 1. page.html
        log.info("[smoke] Scanning page.html: {}", pageHtml.getFileName());
        String pageText = extractText(pageHtml);
        org.junit.jupiter.api.Assertions.assertNotNull(pageText, "extractText returned null on page.html");
        org.junit.jupiter.api.Assertions.assertFalse(pageText.isBlank(), "page.html extracted to blank text");
        log.info("[smoke] page.html extracted: {} chars", pageText.length());

        ContentPiiDetection pageDetection = piiDetectorClient.analyzeContent(pageText);
        org.junit.jupiter.api.Assertions.assertNotNull(pageDetection.sensitiveDataFound(),
            "sensitiveDataFound is null");
        log.info("[smoke] page.html -> {} findings", pageDetection.sensitiveDataFound().size());
        pageDetection.sensitiveDataFound().stream().limit(10).forEach(sd ->
            log.info("[smoke]   page : {} [{}] score={} value={}",
                sd.type(), detectorName(sd), sd.score(), truncate(sd.value(), 60)));

        // 2. premier attachment scannable
        Path attachmentsDir = pageDir.resolve("attachments");
        org.junit.jupiter.api.Assertions.assertTrue(Files.isDirectory(attachmentsDir),
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
        org.junit.jupiter.api.Assertions.assertNotNull(attText,
            "extractText returned null on attachment " + attachment.getFileName());
        org.junit.jupiter.api.Assertions.assertFalse(attText.isBlank(),
            "attachment extracted to blank text: " + attachment.getFileName());
        log.info("[smoke] attachment extracted: {} chars", attText.length());

        ContentPiiDetection attDetection = piiDetectorClient.analyzeContent(attText);
        org.junit.jupiter.api.Assertions.assertNotNull(attDetection.sensitiveDataFound(),
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
    }

    @Test
    @Order(2)
    void runImproved() throws Exception {
        runVariant("improved", "classpath:sql/data-improved.sql");
    }

    /**
     * Re-essaie uniquement les 13 fichiers qui ont failed dans improved mais pas dans baseline.
     * Append les nouveaux findings au findings.jsonl existant et regenere report.md.
     *
     * <p>Run isole :
     * <pre>mvn -Dtest=CorpusDataSqlComparisonIT#retryImprovedFailedFiles ... test</pre>
     */
    @Test
    @Order(3)
    void retryImprovedFailedFiles() throws Exception {
        retryFailedFiles("improved", "classpath:sql/data-improved.sql", IMPROVED_ONLY_FAILED_PATHS);
    }

    /**
     * Re-essaie les 11 fichiers qui ont failed dans baseline. Append au findings.jsonl
     * existant et regenere report.md.
     *
     * <p>Run isole :
     * <pre>mvn -Dtest=CorpusDataSqlComparisonIT#retryBaselineFailedFiles ... test</pre>
     */
    @Test
    @Order(4)
    void retryBaselineFailedFiles() throws Exception {
        retryFailedFiles("baseline", "classpath:data.sql", BASELINE_FAILED_PATHS);
    }

    /**
     * Re-essaie cote improved les 11 paths originellement failed dans baseline.
     * Necessaire pour aligner les 2 variantes : apres retryBaselineFailedFiles, 6 fichiers
     * ont passe cote baseline mais sont toujours failed cote improved (car retryImprovedFailedFiles
     * ne couvre que les 13 improved-only-failed, pas les 11 communs).
     *
     * <p>Les 5 pssec-*.html seront fail-fast (Content too large &gt; 1M), les 6 autres
     * devraient reussir comme cote baseline grace au timeout 900s.
     */
    @Test
    @Order(5)
    void retryBaselineFailedInImproved() throws Exception {
        retryFailedFiles("improved", "classpath:sql/data-improved.sql", BASELINE_FAILED_PATHS);
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
        org.junit.jupiter.api.Assertions.assertTrue(Files.isRegularFile(file),
            "file not found: " + file);

        log.info("[single-biggest] file size: {} bytes", Files.size(file));

        Instant tExtract = Instant.now();
        String text = extractText(file);
        long extractSec = Duration.between(tExtract, Instant.now()).toSeconds();
        org.junit.jupiter.api.Assertions.assertNotNull(text, "extractText returned null");
        org.junit.jupiter.api.Assertions.assertFalse(text.isBlank(), "extractText returned blank");
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
        org.junit.jupiter.api.Assertions.assertFalse(expectedDetectors.isEmpty(),
            "Aucun detecteur actif dans la config — impossible de smoker.");

        ContentPiiDetection detection = piiDetectorClient.analyzeContent(SMOKE_TEXT_FOR_ALL_DETECTORS);
        org.junit.jupiter.api.Assertions.assertNotNull(detection.sensitiveDataFound(),
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
     * Re-essaie chaque path cible (3 tentatives avec backoff), append les findings
     * au {@code findings.jsonl} existant, puis regenere {@code report.md} depuis le
     * fichier mis a jour (en preservant Files skipped et Duration de l'ancien rapport).
     */
    private void retryFailedFiles(String variantName, String sqlClasspath, List<String> targetPaths)
            throws Exception {
        log.info("[retry-{}] === START retry on {} targets ===", variantName, targetPaths.size());
        Instant start = Instant.now();

        resetAndReseedDb(sqlClasspath);
        log.info("[retry-{}] DB reset+reseed done", variantName);

        Path corpusRoot = Paths.get(CORPUS_ROOT).toAbsolutePath();
        Path outDir = Paths.get(OUTPUT_ROOT, variantName).toAbsolutePath();
        Path findingsPath = outDir.resolve("findings.jsonl");
        Path reportPath = outDir.resolve("report.md");
        if (!Files.isRegularFile(findingsPath)) {
            throw new IllegalStateException("findings.jsonl introuvable : " + findingsPath
                + ". Lance d'abord runImproved (ou runBaseline pour la variante baseline).");
        }

        ReportSections oldSections = parseReportSections(reportPath);
        long oldDurationSeconds = parseDurationSeconds(reportPath);
        int oldFilesScanned = parseFilesScanned(reportPath);
        log.info("[retry-{}] old report: failed={} skipped={} filesScanned={}",
            variantName, oldSections.failed().size(), oldSections.skipped().size(), oldFilesScanned);

        // Idempotence : si un path retry-target a deja des findings dans le JSONL, c'est qu'un
        // retry precedent a deja reussi -> on le skip pour eviter le double-append.
        Set<String> pathsAlreadyWithFindings = relativePathsWithFindings(findingsPath);
        List<String> alreadyRetried = new ArrayList<>();
        List<String> toRetry = new ArrayList<>();
        for (String p : targetPaths) {
            if (pathsAlreadyWithFindings.contains(p)) {
                alreadyRetried.add(p);
            } else {
                toRetry.add(p);
            }
        }
        if (!alreadyRetried.isEmpty()) {
            log.info("[retry-{}] skipping {} paths already retried successfully : {}",
                variantName, alreadyRetried.size(), alreadyRetried);
        }
        log.info("[retry-{}] {} paths to (re)try", variantName, toRetry.size());

        List<String> successfullyRetried = new ArrayList<>();
        List<String> permanentFailures = new ArrayList<>();
        int newFindings = 0;

        try (BufferedWriter findingsWriter = Files.newBufferedWriter(findingsPath,
                StandardCharsets.UTF_8, StandardOpenOption.APPEND, StandardOpenOption.CREATE)) {
            for (String relPath : toRetry) {
                int findingsAdded = retryOneFile(variantName, corpusRoot, relPath,
                                                  findingsWriter, successfullyRetried, permanentFailures);
                newFindings += findingsAdded;
            }
        }

        log.info("[retry-{}] === DONE === success={} perm.fail={} newFindings={} duration={}s",
            variantName, successfullyRetried.size(), permanentFailures.size(), newFindings,
            Duration.between(start, Instant.now()).toSeconds());

        // Regenerer report.md depuis findings.jsonl mis a jour
        Stats stats = rebuildStatsFromJsonl(findingsPath);
        stats.skippedFiles.addAll(oldSections.skipped());
        // updated failedFiles : ancien failed - successfullyRetried + nouveaux permanent
        for (String p : oldSections.failed()) {
            if (!successfullyRetried.contains(p)) {
                stats.failedFiles.add(p);
            }
        }
        for (String p : permanentFailures) {
            if (!stats.failedFiles.contains(p)) {
                stats.failedFiles.add(p);
            }
        }
        stats.filesScanned = oldFilesScanned + successfullyRetried.size();

        writeReport(reportPath, variantName, sqlClasspath, stats, Duration.ofSeconds(oldDurationSeconds));
        log.info("[retry-{}] report regenere : {}", variantName, reportPath);
    }

    /** Retry sur 1 fichier, 3 tentatives gRPC avec backoff. Retourne le nb de findings ajoutes. */
    private int retryOneFile(String variantName, Path corpusRoot, String relPath,
                             BufferedWriter findingsWriter,
                             List<String> successfullyRetried, List<String> permanentFailures)
            throws Exception {
        Path file = corpusRoot.resolve(relPath.replace('/', java.io.File.separatorChar));
        if (!Files.isRegularFile(file)) {
            log.warn("[retry-{}] file not found in corpus: {}", variantName, relPath);
            permanentFailures.add(relPath);
            return 0;
        }

        String[] parts = relPath.split("/");
        String piiTypeFolder = parts[0];
        String pageFolder = parts[1];
        Set<String> expectedTypes = EXPECTED_PII_TYPES.getOrDefault(piiTypeFolder, Set.of());
        boolean isAttachment = relPath.contains("/attachments/");
        Path pageDir = corpusRoot.resolve(piiTypeFolder).resolve(pageFolder);
        PageMeta meta = readMeta(pageDir.resolve("meta.json"));

        String text = extractText(file);
        if (text == null || text.isBlank()) {
            log.warn("[retry-{}] empty text from {}", variantName, relPath);
            permanentFailures.add(relPath);
            return 0;
        }

        ContentPiiDetection detection = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                detection = piiDetectorClient.analyzeContent(text);
                break;
            } catch (Throwable t) {
                String msg = t.toString();
                log.warn("[retry-{}] attempt {}/3 failed for {}: {}",
                    variantName, attempt, relPath, msg);
                // Fail-fast sur les erreurs non transitoires (cap de taille cote pii-detector).
                if (msg.contains("INVALID_ARGUMENT") || msg.contains("Content too large")) {
                    log.warn("[retry-{}] non-retryable error, marking permanent failure: {}",
                        variantName, relPath);
                    permanentFailures.add(relPath);
                    return 0;
                }
                if (attempt == 3) {
                    permanentFailures.add(relPath);
                    return 0;
                }
                Thread.sleep(2000L * attempt);
            }
        }

        successfullyRetried.add(relPath);
        int added = 0;
        for (SensitiveData sd : detection.sensitiveDataFound()) {
            boolean expectedHit = expectedTypes.contains(sd.type());
            writeFindingLine(findingsWriter, piiTypeFolder, pageFolder, meta, relPath,
                             isAttachment, text, sd, expectedHit);
            added++;
        }
        log.info("[retry-{}] OK {}: {} findings", variantName, relPath, added);
        return added;
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
        private final Instant startedAt = Instant.now();

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
                "[{}] [PROGRESS] {} {} files {}/{} chars {}/{} ({}%) velocity {} chars/s ETA {} (last: {})",
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
            } catch (Throwable ignored) {
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

        // Pre-extract total char count to drive a meaningful progress bar.
        // We do this in parallel and only sum lengths (no caching of bodies)
        // so RAM stays bounded. Cost: a few minutes of Tika extraction, paid
        // upfront in exchange for a real ETA during the multi-hour scan.
        ProgressTracker progress = precomputeProgressTracker(variantName, corpusRoot);
        this.progressTracker = progress;

        try (BufferedWriter findingsWriter = Files.newBufferedWriter(findingsPath, StandardCharsets.UTF_8)) {
            List<Path> piiTypeDirs = listDirectories(corpusRoot);
            log.info("[{}] {} PII type folders to scan", variantName, piiTypeDirs.size());

            for (Path piiTypeDir : piiTypeDirs) {
                String piiTypeFolder = piiTypeDir.getFileName().toString();
                Set<String> expectedTypes = EXPECTED_PII_TYPES.getOrDefault(piiTypeFolder, Set.of());

                List<Path> pageDirs = listDirectories(piiTypeDir);
                log.info("[{}] [{}] {} pages", variantName, piiTypeFolder, pageDirs.size());

                for (Path pageDir : pageDirs) {
                    scanPage(variantName, corpusRoot, piiTypeFolder, expectedTypes, pageDir, stats, findingsWriter);
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

    private void scanPage(String variantName, Path corpusRoot, String piiTypeFolder, Set<String> expectedTypes,
                          Path pageDir, Stats stats, BufferedWriter findingsWriter) throws IOException {
        PageMeta meta = readMeta(pageDir.resolve("meta.json"));
        PageStats pageStats = new PageStats(piiTypeFolder, pageDir.getFileName().toString(), meta);

        List<Path> filesToScan = collectScannableFiles(pageDir);

        for (Path file : filesToScan) {
            String relPath = corpusRoot.relativize(file).toString().replace('\\', '/');
            boolean isAttachment = file.getParent().getFileName().toString().equals("attachments")
                || (file.getParent().getParent() != null
                    && "attachments".equals(file.getParent().getParent().getFileName().toString()));

            String text = extractText(file);
            if (text == null || text.isBlank()) {
                stats.skippedFiles.add(relPath);
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
        }

        stats.pages.add(pageStats);
        RecallCounter rc = stats.recallByPiiType.computeIfAbsent(piiTypeFolder, k -> new RecallCounter());
        rc.totalPages++;
        if (pageStats.expectedFindings > 0) {
            rc.hitPages++;
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
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
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

    /**
     * Lit findings.jsonl et retourne l'ensemble des {@code relativePath} qui ont
     * au moins 1 finding. Sert a l'idempotence du retry : un path ayant deja des
     * findings ne doit pas etre re-soumis (sinon double-append).
     */
    private static Set<String> relativePathsWithFindings(Path findingsPath) throws IOException {
        Set<String> paths = new HashSet<>();
        if (!Files.isRegularFile(findingsPath)) {
            return paths;
        }
        try (Stream<String> lines = Files.lines(findingsPath, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                try {
                    com.fasterxml.jackson.databind.JsonNode n = MAPPER.readTree(line);
                    if (n.has("relativePath")) {
                        paths.add(n.get("relativePath").asText());
                    }
                } catch (IOException ignored) {
                    // ligne malformee, on l'ignore
                }
            });
        }
        return paths;
    }

    /** Sections extraites d'un report.md existant (pour le mode retry). */
    private record ReportSections(List<String> failed, List<String> skipped) {}

    /** Parse les sections "Files failed" et "Files skipped" d'un report.md existant. */
    private static ReportSections parseReportSections(Path reportPath) throws IOException {
        List<String> failed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        if (!Files.isRegularFile(reportPath)) {
            return new ReportSections(failed, skipped);
        }
        String current = null;
        for (String line : Files.readAllLines(reportPath, StandardCharsets.UTF_8)) {
            if (line.startsWith("## Files failed")) {
                current = "failed";
            } else if (line.startsWith("## Files skipped")) {
                current = "skipped";
            } else if (line.startsWith("## ")) {
                current = null;
            } else if (line.startsWith("- ") && current != null) {
                String p = line.substring(2).trim();
                if ("failed".equals(current)) {
                    failed.add(p);
                } else {
                    skipped.add(p);
                }
            }
        }
        return new ReportSections(failed, skipped);
    }

    private static final Pattern DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d+)s");
    private static final Pattern FILES_SCANNED_PATTERN = Pattern.compile("Files scanned:\\s*(\\d+)");

    private static long parseDurationSeconds(Path reportPath) throws IOException {
        if (!Files.isRegularFile(reportPath)) {
            return 0L;
        }
        for (String line : Files.readAllLines(reportPath, StandardCharsets.UTF_8)) {
            Matcher m = DURATION_PATTERN.matcher(line);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
        }
        return 0L;
    }

    private static int parseFilesScanned(Path reportPath) throws IOException {
        if (!Files.isRegularFile(reportPath)) {
            return 0;
        }
        for (String line : Files.readAllLines(reportPath, StandardCharsets.UTF_8)) {
            Matcher m = FILES_SCANNED_PATTERN.matcher(line);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return 0;
    }

    /**
     * Reconstruit l'objet Stats en relisant l'integralite du findings.jsonl.
     * Note : {@code filesScanned} n'est PAS recalcule ici (sous-estime car le JSONL n'a
     * que les fichiers avec ≥ 1 finding) — l'appelant doit le definir explicitement.
     */
    private static Stats rebuildStatsFromJsonl(Path findingsPath) throws IOException {
        Stats s = new Stats();
        Map<String, PageStats> pageByKey = new LinkedHashMap<>();
        try (Stream<String> lines = Files.lines(findingsPath, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                try {
                    com.fasterxml.jackson.databind.JsonNode n = MAPPER.readTree(line);
                    String piiTypeFolder = n.get("piiTypeFolder").asText();
                    String pageFolder = n.get("pageFolder").asText();
                    String piiType = n.get("piiTypeDetected").asText();
                    String detector = n.has("detector") ? n.get("detector").asText() : "UNKNOWN";
                    String relPath = n.get("relativePath").asText();
                    boolean expectedHit = n.has("expectedHit") && n.get("expectedHit").asBoolean();

                    s.totalFindings++;
                    s.countByPiiType.merge(piiType, 1, Integer::sum);
                    s.countByDetector.merge(detector, 1, Integer::sum);
                    s.countByFile.merge(relPath, 1, Integer::sum);

                    String pageKey = piiTypeFolder + "|" + pageFolder;
                    PageStats ps = pageByKey.get(pageKey);
                    if (ps == null) {
                        PageMeta meta = new PageMeta(
                            jsonTextOrNull(n, "pageTitle"),
                            jsonTextOrNull(n, "pageUrl"),
                            jsonTextOrNull(n, "pageId"));
                        ps = new PageStats(piiTypeFolder, pageFolder, meta);
                        pageByKey.put(pageKey, ps);
                    }
                    if (expectedHit) {
                        ps.expectedFindings++;
                    } else {
                        ps.otherFindings++;
                    }
                    ps.types.add(piiType);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
        s.pages.addAll(pageByKey.values());

        // Recall : count distinct pages par piiTypeFolder + hits
        Map<String, RecallCounter> recall = new TreeMap<>();
        for (PageStats ps : s.pages) {
            RecallCounter rc = recall.computeIfAbsent(ps.piiTypeFolder, k -> new RecallCounter());
            rc.totalPages++;
            if (ps.expectedFindings > 0) {
                rc.hitPages++;
            }
        }
        s.recallByPiiType.putAll(recall);
        return s;
    }

    private static String jsonTextOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
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
