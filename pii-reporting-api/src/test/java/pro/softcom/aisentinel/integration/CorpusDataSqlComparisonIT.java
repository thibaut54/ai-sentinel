package pro.softcom.aisentinel.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.tika.Tika;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
        // 900s (15 min) requis pour les Excel volumineux du corpus (ex: Saga-Appareil*.xlsx
        // genere ~1k findings, depasse les 5 min par defaut, cf. DEADLINE_EXCEEDED sur le retry).
        registry.add("pii-detector.connection-timeout-ms",    () -> "900000");
        registry.add("pii-detector.request-timeout-ms",       () -> "900000");

        registry.add("PII_DATABASE_ENCRYPTION_KEY",
            () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("PII_REPORTING_ALLOW_SECRET_REVEAL", () -> "false");
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PiiDetectorClient piiDetectorClient;
    @Autowired private HtmlContentParser htmlContentParser;

    private static final Tika TIKA = new Tika();
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

            ContentPiiDetection detection;
            try {
                detection = piiDetectorClient.analyzeContent(text);
            } catch (Throwable t) {
                log.warn("[{}] analyze failed for {}: {}", variantName, relPath, t.toString());
                stats.failedFiles.add(relPath);
                continue;
            }

            stats.filesScanned++;

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
            if (name.endsWith(".html") || name.endsWith(".htm")) {
                return htmlContentParser.cleanText(Files.readString(file, StandardCharsets.UTF_8));
            }
            return TIKA.parseToString(file);
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
