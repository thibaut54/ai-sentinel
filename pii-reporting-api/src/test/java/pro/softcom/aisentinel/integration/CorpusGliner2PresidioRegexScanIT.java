package pro.softcom.aisentinel.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Assertions;
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
import org.testcontainers.containers.output.OutputFrame;
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
import java.io.InputStream;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Scanne le corpus Confluence hiérarchique ({@code src/test/resources/corpus/}) avec le
 * pipeline complet (backend Java + pii-detector Python + Postgres) en utilisant le seed
 * {@code data-improved-gliner2-presidio-regex.sql} (détecteurs actifs : GLINER2 + PRESIDIO
 * + REGEX ; GLiNER v1, OpenMed et LLM-judge désactivés).
 *
 * <p>Version nettoyée de {@code CorpusDataSqlComparisonIT} : un seul variant SQL, pas de
 * machinerie de reprise/multi-variant. Deux apports :
 * <ul>
 *   <li><b>Estimation du temps total</b> ({@link #scanCorpusWithEstimate}) : un warmup mesure
 *       la vitesse d'analyse (chars/s) séparément sur la plus grosse page HTML et la plus
 *       grosse pièce jointe, puis le corpus est parcouru pour compter le nombre total de
 *       caractères à scanner. L'ETA est {@code totalPageChars/vitessePage +
 *       totalAttachmentChars/vitessePJ}.</li>
 *   <li><b>Validation du timeout</b> ({@link #smokeBiggestPageAndAttachment}) : scanne la
 *       plus grosse page et la plus grosse pièce jointe pour vérifier que le timeout gRPC
 *       maximal ({@link #MAX_TIMEOUT_MS}) est suffisant pour le pire cas du corpus.</li>
 * </ul>
 *
 * <p>Structure du corpus :
 * <pre>
 *   corpus/
 *     &lt;PII_TYPE_FOLDER&gt;/
 *       &lt;page_folder&gt;/
 *         page.html              (scannée via HtmlContentParser)
 *         meta.json              (titre + url, non scanné)
 *         attachments/*.pdf|...  (scannées via Tika ; images/archives exclues)
 * </pre>
 *
 * <p>Sorties : {@code target/corpus-gliner2-presidio-regex/findings.jsonl} + {@code report.md}.
 */
@Testcontainers
@SpringBootTest(classes = AiSentinelApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CorpusGliner2PresidioRegexScanIT {

    private static final Logger log = LoggerFactory.getLogger(CorpusGliner2PresidioRegexScanIT.class);

    private static final String CORPUS_ROOT = "src/test/resources/corpus";
    private static final String OUTPUT_ROOT = "target/corpus-gliner2-presidio-regex";
    private static final String SQL_SEED = "classpath:sql/data-improved-gliner2-presidio-regex.sql";
    private static final int CONTEXT_CHARS = 200;
    private static final int GRPC_PORT = 50051;

    /**
     * Timeout gRPC maximal côté client. Le {@link #smokeBiggestPageAndAttachment} valide
     * qu'il couvre le pire cas du corpus. Mesuré empiriquement : GLiNER2-large + Presidio
     * sur CPU tournent à ~190 chars/s, donc le pire fichier analysable (~1M chars, juste
     * sous {@link #MAX_TEXT_SIZE}) prend ~90 min. 2h laissent une marge de sécurité.
     */
    private static final long MAX_TIMEOUT_MS = 7_200_000L;

    /**
     * Cap de taille de contenu côté pii-detector ({@code PiiService.max_text_size}, 1M chars).
     * Au-delà, le détecteur rejette immédiatement avec "Content too large" : ces fichiers ne
     * sont donc ni un cas de timeout (rejet instantané) ni du temps d'analyse à estimer. On les
     * exclut des vitesses/ETA et on les saute proprement dans le scan.
     */
    private static final int MAX_TEXT_SIZE = 1_000_000;

    /**
     * Taille cible (chars) des fichiers échantillons utilisés pour mesurer la vitesse au
     * warmup du test global. On ne mesure PAS sur le plus gros fichier analysable (~1M chars
     * ≈ 90 min à ~190 chars/s sur CPU) : cela rendrait le warmup interminable. Un fichier
     * "gros mais borné" (~100k chars) donne une vitesse chars/s stable en ~10 min, suffisant
     * pour une ETA crédible. Le smoke, lui, scanne bien le plus gros fichier (validation du
     * timeout pire cas).
     */
    private static final int VELOCITY_SAMPLE_TARGET_CHARS = 100_000;

    /**
     * Threads PyTorch du serveur pii-detector. Par défaut le serveur bride à 4
     * ({@code TORCH_NUM_THREADS}, pii_service.py) alors que Docker Desktop alloue 8 CPU ici —
     * on aligne sur les CPU disponibles pour ne pas laisser la moitié du CPU inactive. Sans
     * impact sur les détections (pure parallélisation des forward passes CPU). ⚠ Doit
     * correspondre à l'allocation CPU de Docker Desktop ; ne pas dépasser (oversubscription).
     */
    private static final String DETECTOR_TORCH_THREADS = "8";

    private static final String POSTGRES_ALIAS = "postgres-it";
    private static final String DB_NAME = "ai-sentinel";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";

    /** Dossier de profondeur 1 -> codes de PII type qui valident un hit de recall. */
    private static final Map<String, Set<String>> EXPECTED_PII_TYPES = Map.of(
        "AVS_NUMBER",                                 Set.of("AVS_NUMBER"),
        "Adresse_MAC",                                Set.of("MAC_ADDRESS"),
        "Carte_de_credit",                            Set.of("CREDIT_CARD_NUMBER", "CREDIT_CARD", "PAYMENT_CARD", "CARD_NUMBER"),
        "Identifiant_bancaire_international_IBAN",     Set.of("IBAN", "IBAN_CODE"),
        "Identifiant_systeme_ou_compte_de_connexion", Set.of("USERNAME", "PASSWORD", "API_KEY", "ACCESS_TOKEN", "SECRET"),
        "MEDICAL_LICENSE",                            Set.of("MEDICAL_LICENSE"),
        "Plaque_d_immatriculation",                   Set.of("LICENSE_PLATE", "VEHICLE_REGISTRATION"),
        "SESSION_ID",                                 Set.of("SESSION_ID"),
        "SOCIALNUM",                                  Set.of("SOCIALNUM"),
        "TAX_ID",                                     Set.of("TAX_ID", "TAX_NUMBER")
    );

    /** Extensions ignorées avant toute IO — images, archives, binaires opaques. */
    private static final Set<String> EXCLUDED_EXT = Set.of(
        ".png", ".jpg", ".jpeg", ".gif", ".svg", ".bmp",
        ".zip", ".gz", ".tar", ".7z",
        ".kdbx", ".ipa", ".crt", ".mobileconfig"
    );

    private static final Path HF_CACHE_DIR = Paths.get(
        System.getProperty("corpus.bench.hf-cache",
            System.getProperty("user.home") + "/.ai-sentinel-it-hf-cache"));

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Network NETWORK = Network.newNetwork();
    private static final Logger CONTAINER_LOG = LoggerFactory.getLogger("pii-detector-container");

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
        // Aligne les threads torch sur les CPU Docker (défaut serveur = 4, sous-utilise le CPU).
        .withEnv("TORCH_NUM_THREADS", DETECTOR_TORCH_THREADS)
        // Python écrit tout (y compris INFO) sur stderr ; on parse le niveau réel pour ne
        // pas inonder le log de faux ERROR (cf. routeContainerLog).
        .withLogConsumer(CorpusGliner2PresidioRegexScanIT::routeContainerLog)
        .waitingFor(Wait.forLogMessage(".*Server started on port.*", 1))
        .withStartupTimeout(Duration.ofMinutes(10));

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",               postgres::getJdbcUrl);
        registry.add("spring.datasource.username",          postgres::getUsername);
        registry.add("spring.datasource.password",          postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto",       () -> "update");
        registry.add("spring.jpa.show-sql",                 () -> "false");
        registry.add("spring.jpa.properties.hibernate.dialect",
                                                            () -> "org.hibernate.dialect.PostgreSQLDialect");

        registry.add("pii-detector.host", piiDetector::getHost);
        registry.add("pii-detector.port", () -> piiDetector.getMappedPort(GRPC_PORT));
        registry.add("pii-detector.connection-timeout-ms", () -> String.valueOf(MAX_TIMEOUT_MS));
        registry.add("pii-detector.request-timeout-ms",    () -> String.valueOf(MAX_TIMEOUT_MS));

        registry.add("PII_DATABASE_ENCRYPTION_KEY",
            () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("PII_REPORTING_ALLOW_SECRET_REVEAL", () -> "false");
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PiiDetectorClient piiDetectorClient;
    @Autowired private HtmlContentParser htmlContentParser;

    /** Inventaire du corpus (extraction Tika de tous les fichiers), partagé entre tests. */
    private static volatile CorpusInventory cachedInventory;

    // ========================================================================
    // Tests
    // ========================================================================

    /**
     * Smoke test : scanne la plus grosse page HTML et la plus grosse pièce jointe du corpus
     * (par nombre de caractères extraits) afin de vérifier que {@link #MAX_TIMEOUT_MS} couvre
     * le pire cas. Logge la marge restante (headroom) timeout/temps réel.
     *
     * <pre>mvn -Dtest=CorpusGliner2PresidioRegexScanIT#smokeBiggestPageAndAttachment ... test</pre>
     */
    @Test
    @Order(0)
    void smokeBiggestPageAndAttachment() throws Exception {
        log.info("[smoke] === START ===");
        resetAndReseedDb();
        warmupModels("[smoke]");

        CorpusInventory inv = inventory();
        Assertions.assertNotNull(inv.biggestPage(), "Aucune page HTML scannable dans le corpus");
        Assertions.assertNotNull(inv.biggestAttachment(), "Aucune pièce jointe scannable dans le corpus");

        smokeScanOne("[smoke] page    ", inv.biggestPage());
        smokeScanOne("[smoke] attach. ", inv.biggestAttachment());

        log.info("[smoke] === DONE — timeout max {} ms suffisant pour le pire cas ===", MAX_TIMEOUT_MS);
    }

    /**
     * Scanne tout le corpus après avoir estimé et loggé le temps total attendu.
     *
     * <p>Étapes :
     * <ol>
     *   <li>warmup (force le chargement des modèles) ;</li>
     *   <li>mesure de vitesse (chars/s) sur la plus grosse page et la plus grosse PJ ;</li>
     *   <li>comptage du total de caractères du corpus (page vs attachment) ;</li>
     *   <li>log de l'ETA = totalPageChars/vitessePage + totalAttachmentChars/vitessePJ ;</li>
     *   <li>scan complet -> findings.jsonl + report.md, avec barre de progression live.</li>
     * </ol>
     *
     * <pre>mvn -Dtest=CorpusGliner2PresidioRegexScanIT#scanCorpusWithEstimate ... test</pre>
     */
    @Test
    @Order(1)
    void scanCorpusWithEstimate() throws Exception {
        log.info("[scan] === START ===");
        Instant start = Instant.now();
        resetAndReseedDb();
        warmupModels("[scan]");

        CorpusInventory inv = inventory();
        Velocity velocity = measureVelocity(inv);

        Path outDir = Paths.get(OUTPUT_ROOT).toAbsolutePath();
        Files.createDirectories(outDir);
        Path findingsPath = outDir.resolve("findings.jsonl");
        Path processedPath = outDir.resolve("processed.txt");
        Path reportPath = outDir.resolve("report.md");

        // Reprise : si un run précédent a laissé des sorties, on saute les fichiers déjà traités
        // et on replie leurs findings dans les stats pour que le report final reste exact.
        Stats stats = new Stats();
        Set<String> doneFiles = new HashSet<>();
        Map<String, int[]> priorPageCounts = new HashMap<>();
        boolean resuming = Files.exists(processedPath)
            || (Files.exists(findingsPath) && Files.size(findingsPath) > 0);
        loadPriorRun(findingsPath, processedPath, stats, doneFiles, priorPageCounts);

        // ETA + barre de progression sur le RESTANT (fichiers analysables non encore faits).
        List<FileEntry> remaining = inv.analyzableFiles().stream()
            .filter(e -> !doneFiles.contains(e.relPath()))
            .toList();
        long remainingPageChars = remaining.stream().filter(e -> !e.attachment())
            .mapToLong(FileEntry::chars).sum();
        long remainingAttachmentChars = remaining.stream().filter(FileEntry::attachment)
            .mapToLong(FileEntry::chars).sum();
        logEstimate(inv, velocity, remainingPageChars, remainingAttachmentChars, resuming);

        ProgressTracker progress = new ProgressTracker(
            remainingPageChars + remainingAttachmentChars, remaining.size());

        // Append (pas truncate) en reprise pour préserver les findings antérieurs.
        OpenOption[] findingsOpts = resuming
            ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE}
            : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE};

        Path corpusRoot = Paths.get(CORPUS_ROOT).toAbsolutePath();
        try (BufferedWriter findingsWriter = Files.newBufferedWriter(findingsPath, StandardCharsets.UTF_8, findingsOpts);
             BufferedWriter processedWriter = Files.newBufferedWriter(processedPath, StandardCharsets.UTF_8,
                 StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {

            ResumeState resume = new ResumeState(doneFiles, priorPageCounts, processedWriter);
            log.info("[scan] RESUME={} : {} fichiers déjà faits (sautés), {} findings antérieurs rechargés",
                resuming, doneFiles.size(), stats.totalFindings);

            for (Path piiTypeDir : listDirectories(corpusRoot)) {
                String piiTypeFolder = piiTypeDir.getFileName().toString();
                Set<String> expectedTypes = EXPECTED_PII_TYPES.getOrDefault(piiTypeFolder, Set.of());
                for (Path pageDir : listDirectories(piiTypeDir)) {
                    scanPage(corpusRoot, piiTypeFolder, expectedTypes, pageDir, stats, findingsWriter,
                        progress, resume);
                }
            }
        }

        writeReport(reportPath, stats, Duration.between(start, Instant.now()));
        log.info("[scan] === DONE === findings={} files={} duration={}s -> {}",
            stats.totalFindings, stats.filesScanned,
            Duration.between(start, Instant.now()).toSeconds(), outDir);
    }

    // ========================================================================
    // Warmup / vitesse / estimation
    // ========================================================================

    /** Force le chargement de tous les détecteurs via un scan minuscule, hors mesure. */
    private void warmupModels(String tag) {
        Instant t0 = Instant.now();
        ContentPiiDetection warm = piiDetectorClient.analyzeContent(
            "Warmup: IBAN CH9300762011623852957 (force detectors to load).");
        log.info("{} warmup OK in {}s : {} findings", tag,
            Duration.between(t0, Instant.now()).toSeconds(), warm.sensitiveDataFound().size());
    }

    private record Velocity(double pageCharsPerSec, double attachmentCharsPerSec) {}

    /**
     * Mesure la vitesse d'analyse (chars/s) sur un échantillon "gros mais borné"
     * ({@link #VELOCITY_SAMPLE_TARGET_CHARS}) de page et de pièce jointe — pas sur le plus
     * gros fichier, qui prendrait ~90 min. La vitesse chars/s est stable au-delà de quelques
     * dizaines de milliers de chars, donc l'échantillon borné donne une ETA crédible
     * rapidement.
     */
    private Velocity measureVelocity(CorpusInventory inv) {
        FileEntry pageSample = velocitySample(inv, false);
        FileEntry attSample = velocitySample(inv, true);
        Assertions.assertNotNull(pageSample, "Aucune page analysable pour mesurer la vitesse");
        Assertions.assertNotNull(attSample, "Aucune pièce jointe analysable pour mesurer la vitesse");
        double pageVel = measureOne("[scan] vitesse page   ", pageSample);
        double attVel = measureOne("[scan] vitesse attach.", attSample);
        return new Velocity(pageVel, attVel);
    }

    /**
     * Choisit le fichier échantillon pour la mesure de vitesse : le plus gros fichier
     * ≤ {@link #VELOCITY_SAMPLE_TARGET_CHARS} de la catégorie demandée (page ou PJ), ou à
     * défaut le plus petit disponible si tous dépassent la cible.
     */
    private FileEntry velocitySample(CorpusInventory inv, boolean attachment) {
        List<FileEntry> candidates = inv.analyzableFiles().stream()
            .filter(e -> e.attachment() == attachment)
            .toList();
        return candidates.stream()
            .filter(e -> e.chars() <= VELOCITY_SAMPLE_TARGET_CHARS)
            .max(Comparator.comparingInt(FileEntry::chars))
            .orElseGet(() -> candidates.stream()
                .min(Comparator.comparingInt(FileEntry::chars))
                .orElse(null));
    }

    /** Scanne un fichier, logge sa vitesse, et retourne chars/s (>0). */
    private double measureOne(String tag, FileEntry entry) {
        String text = extractText(entry.path());
        Assertions.assertNotNull(text, "extractText null sur " + entry.relPath());
        Instant t0 = Instant.now();
        ContentPiiDetection detection = piiDetectorClient.analyzeContent(text);
        double sec = Math.max(0.001, Duration.between(t0, Instant.now()).toMillis() / 1000.0);
        double charsPerSec = text.length() / sec;
        log.info("{} : {} chars en {}s -> {} chars/s ({} findings) [{}]",
            tag, text.length(), String.format(Locale.ROOT, "%.1f", sec),
            String.format(Locale.ROOT, "%.0f", charsPerSec),
            detection.sensitiveDataFound().size(), entry.relPath());
        return charsPerSec;
    }

    private void smokeScanOne(String tag, FileEntry entry) {
        String text = extractText(entry.path());
        Assertions.assertNotNull(text, "extractText null sur " + entry.relPath());
        Assertions.assertFalse(text.isBlank(), "texte vide après extraction : " + entry.relPath());

        Instant t0 = Instant.now();
        ContentPiiDetection detection = piiDetectorClient.analyzeContent(text);
        long ms = Duration.between(t0, Instant.now()).toMillis();
        Assertions.assertNotNull(detection.sensitiveDataFound(),
            "sensitiveDataFound null sur " + entry.relPath());

        double headroomPct = 100.0 * (MAX_TIMEOUT_MS - ms) / MAX_TIMEOUT_MS;
        log.info("{} : {} chars analysés en {}ms ({} findings) — headroom timeout {}% [{}]",
            tag, text.length(), ms, detection.sensitiveDataFound().size(),
            String.format(Locale.ROOT, "%.1f", headroomPct), entry.relPath());
        Assertions.assertTrue(ms < MAX_TIMEOUT_MS,
            "Analyse de " + entry.relPath() + " (" + ms + "ms) >= timeout max " + MAX_TIMEOUT_MS
            + "ms — augmenter MAX_TIMEOUT_MS.");
    }

    /**
     * Logge l'estimation du temps à partir des vitesses mesurées. En reprise, l'ETA porte sur le
     * <b>restant</b> ({@code remainingPageChars}/{@code remainingAttachmentChars}), pas sur le total.
     */
    private void logEstimate(CorpusInventory inv, Velocity velocity,
                             long remainingPageChars, long remainingAttachmentChars, boolean resuming) {
        long pageEtaSec = velocity.pageCharsPerSec() > 0
            ? (long) (remainingPageChars / velocity.pageCharsPerSec()) : -1;
        long attEtaSec = velocity.attachmentCharsPerSec() > 0
            ? (long) (remainingAttachmentChars / velocity.attachmentCharsPerSec()) : -1;
        long totalEtaSec = Math.max(0, pageEtaSec) + Math.max(0, attEtaSec);

        log.info("[scan] [ESTIMATE] corpus : {} fichiers analysables, {} chars total "
                + "({} pages / {} pièces jointes) ; {} fichiers hors-cap ignorés",
            inv.totalFiles(), inv.totalChars(), inv.totalPageChars(), inv.totalAttachmentChars(),
            inv.oversizedFiles().size());
        log.info("[scan] [ESTIMATE] {} : {} chars ({} pages / {} pièces jointes)",
            resuming ? "RESTANT (reprise)" : "à scanner",
            remainingPageChars + remainingAttachmentChars, remainingPageChars, remainingAttachmentChars);
        log.info("[scan] [ESTIMATE] vitesse : {} chars/s (pages) / {} chars/s (pièces jointes)",
            String.format(Locale.ROOT, "%.0f", velocity.pageCharsPerSec()),
            String.format(Locale.ROOT, "%.0f", velocity.attachmentCharsPerSec()));
        log.info("[scan] [ESTIMATE] temps estimé {} : pages {} + pièces jointes {} = TOTAL {}",
            resuming ? "restant" : "",
            formatDuration(pageEtaSec), formatDuration(attEtaSec), formatDuration(totalEtaSec));
    }


    // ========================================================================
    // Inventaire du corpus
    // ========================================================================

    private record FileEntry(String relPath, Path path, boolean attachment, int chars) {}

    /**
     * Inventaire du corpus. Les totaux et les "plus gros" fichiers ne portent que sur les
     * fichiers <b>analysables</b> (≤ {@link #MAX_TEXT_SIZE}) : les fichiers hors-cap sont rejetés
     * instantanément par le détecteur et ne comptent ni dans les vitesses ni dans l'ETA.
     *
     * @param analyzableFiles    fichiers ≤ cap (réellement scannés)
     * @param oversizedFiles     fichiers > cap (rejetés "Content too large", listés à part)
     * @param biggestPage        plus grosse page analysable (pire cas timeout côté pages)
     * @param biggestAttachment  plus grosse pièce jointe analysable (pire cas timeout côté PJ)
     * @param totalPageChars     somme des chars des pages analysables
     * @param totalAttachmentChars somme des chars des pièces jointes analysables
     */
    private record CorpusInventory(
        List<FileEntry> analyzableFiles,
        List<FileEntry> oversizedFiles,
        FileEntry biggestPage,
        FileEntry biggestAttachment,
        long totalPageChars,
        long totalAttachmentChars
    ) {
        int totalFiles() {
            return analyzableFiles.size();
        }

        long totalChars() {
            return totalPageChars + totalAttachmentChars;
        }
    }

    /** Construit (et met en cache) l'inventaire : extrait tous les fichiers en parallèle. */
    private CorpusInventory inventory() throws IOException {
        CorpusInventory local = cachedInventory;
        if (local != null) {
            return local;
        }
        synchronized (CorpusGliner2PresidioRegexScanIT.class) {
            if (cachedInventory != null) {
                return cachedInventory;
            }
            cachedInventory = buildInventory();
            return cachedInventory;
        }
    }

    private CorpusInventory buildInventory() throws IOException {
        Instant t0 = Instant.now();
        log.info("[inventory] parcours du corpus (extraction Tika de tous les fichiers)...");
        Path corpusRoot = Paths.get(CORPUS_ROOT).toAbsolutePath();

        List<Path> allFiles = new ArrayList<>();
        for (Path piiTypeDir : listDirectories(corpusRoot)) {
            for (Path pageDir : listDirectories(piiTypeDir)) {
                allFiles.addAll(collectScannableFiles(pageDir));
            }
        }

        List<FileEntry> entries = allFiles.parallelStream()
            .map(file -> {
                String text = extractText(file);
                if (text == null || text.isBlank()) {
                    return null;
                }
                String relPath = corpusRoot.relativize(file).toString().replace('\\', '/');
                return new FileEntry(relPath, file, isAttachment(file), text.length());
            })
            .filter(Objects::nonNull)
            .toList();

        List<FileEntry> analyzable = entries.stream().filter(e -> e.chars() <= MAX_TEXT_SIZE).toList();
        List<FileEntry> oversized = entries.stream().filter(e -> e.chars() > MAX_TEXT_SIZE).toList();

        long totalPageChars = analyzable.stream().filter(e -> !e.attachment())
            .mapToLong(FileEntry::chars).sum();
        long totalAttachmentChars = analyzable.stream().filter(FileEntry::attachment)
            .mapToLong(FileEntry::chars).sum();
        FileEntry biggestPage = analyzable.stream().filter(e -> !e.attachment())
            .max(Comparator.comparingInt(FileEntry::chars)).orElse(null);
        FileEntry biggestAttachment = analyzable.stream().filter(FileEntry::attachment)
            .max(Comparator.comparingInt(FileEntry::chars)).orElse(null);

        log.info("[inventory] {} fichiers analysables (≤ {} chars), {} hors-cap, {} chars total "
                + "(pré-calcul {}s)",
            analyzable.size(), MAX_TEXT_SIZE, oversized.size(), totalPageChars + totalAttachmentChars,
            Duration.between(t0, Instant.now()).toSeconds());
        if (biggestPage != null) {
            log.info("[inventory] plus grosse page analysable    : {} chars [{}]",
                biggestPage.chars(), biggestPage.relPath());
        }
        if (biggestAttachment != null) {
            log.info("[inventory] plus grosse pièce J. analysable : {} chars [{}]",
                biggestAttachment.chars(), biggestAttachment.relPath());
        }
        oversized.forEach(e -> log.info("[inventory] HORS-CAP (rejet Content too large) : {} chars [{}]",
            e.chars(), e.relPath()));
        return new CorpusInventory(analyzable, oversized, biggestPage, biggestAttachment,
            totalPageChars, totalAttachmentChars);
    }

    // ========================================================================
    // Scan d'une page
    // ========================================================================

    private void scanPage(Path corpusRoot, String piiTypeFolder, Set<String> expectedTypes,
                          Path pageDir, Stats stats, BufferedWriter findingsWriter, ProgressTracker progress,
                          ResumeState resume) throws IOException {
        PageMeta meta = readMeta(pageDir.resolve("meta.json"));
        // Replie les findings "expected" d'un run précédent sur cette page pour que le recall
        // reste correct (ces fichiers sont sautés ci-dessous, donc non recomptés depuis le live).
        int[] prior = resume.priorPageCounts().get(piiTypeFolder + "/" + pageDir.getFileName());
        int expectedFindingsOnPage = prior != null ? prior[0] : 0;

        for (Path file : collectScannableFiles(pageDir)) {
            String relPath = corpusRoot.relativize(file).toString().replace('\\', '/');
            if (resume.doneFiles().contains(relPath)) {
                continue; // déjà traité lors d'un run précédent — reprise
            }
            boolean attachment = isAttachment(file);

            String text = extractText(file);
            if (text == null || text.isBlank()) {
                stats.skippedFiles.add(relPath);
                resume.markProcessed(relPath); // vide = déterministe, ne pas réessayer en reprise
                continue;
            }
            if (text.length() > MAX_TEXT_SIZE) {
                // Rejeté d'office par le détecteur ("Content too large") — on évite le transfert.
                stats.oversizedFiles.add(relPath + " (" + text.length() + " chars)");
                resume.markProcessed(relPath); // déterministe — ne pas réessayer
                continue;
            }

            Instant t0 = Instant.now();
            ContentPiiDetection detection = analyzeWithRetry(relPath, text);
            long analyzeMs = Duration.between(t0, Instant.now()).toMillis();
            if (detection == null) {
                // Échec d'analyse : NON marqué processed -> sera réessayé à la reprise.
                stats.failedFiles.add(relPath);
                progress.recordFile(text.length(), analyzeMs, false);
                progress.logProgress(relPath);
                continue;
            }

            stats.filesScanned++;
            progress.recordFile(text.length(), analyzeMs, true);
            progress.logProgress(relPath);

            for (SensitiveData sd : detection.sensitiveDataFound()) {
                boolean expectedHit = expectedTypes.contains(sd.type());
                writeFindingLine(findingsWriter, piiTypeFolder, pageDir.getFileName().toString(),
                    meta, relPath, attachment, text, sd, expectedHit);
                stats.totalFindings++;
                stats.countByPiiType.merge(sd.type(), 1, Integer::sum);
                stats.countByDetector.merge(detectorName(sd), 1, Integer::sum);
                stats.countByFile.merge(relPath, 1, Integer::sum);
                if (expectedHit) {
                    expectedFindingsOnPage++;
                }
            }
            findingsWriter.flush(); // durable par fichier pour que la reprise garde les résultats
            resume.markProcessed(relPath);
        }

        stats.pagesScanned++;
        RecallCounter rc = stats.recallByPiiType.computeIfAbsent(piiTypeFolder, key -> new RecallCounter());
        rc.totalPages++;
        if (expectedFindingsOnPage > 0) {
            rc.hitPages++;
        }
    }

    /**
     * État porté pendant un run reprenable.
     *
     * @param doneFiles       chemins relatifs déjà traités (succès, vide ou hors-cap) — sautés.
     * @param priorPageCounts {@code "piiTypeFolder/pageFolder" -> [expectedFindings, otherFindings]}
     *                        rechargé depuis findings.jsonl pour garder le recall correct.
     * @param processedWriter sidecar append-only ; chaque fichier traité est flushé immédiatement
     *                        pour qu'un kill brutal conserve le point de reprise.
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
     * Pré-charge le skip-set et les stats agrégées depuis les sorties d'un run précédent.
     *
     * <ul>
     *   <li>{@code processed.txt} : tout fichier pleinement traité (y compris 0 finding / vide /
     *       hors-cap), source du skip-set.</li>
     *   <li>{@code findings.jsonl} : chaque finding est replié dans {@code stats} (totaux, par type,
     *       par détecteur, par fichier, recall par page) pour que le report final reste exact.</li>
     * </ul>
     *
     * <p>Les fichiers en ÉCHEC d'un run précédent n'apparaissent dans aucune des deux sources :
     * ils sont donc naturellement réessayés.
     */
    private void loadPriorRun(Path findingsPath, Path processedPath, Stats stats,
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
        log.info("[scan] loadPriorRun : {} findings antérieurs sur {} fichiers, {} fichiers dans le skip-set",
            stats.totalFindings, filesWithFindings.size(), doneFiles.size());
    }

    /**
     * Wrappe {@link PiiDetectorClient#analyzeContent} avec un petit retry sur erreur gRPC
     * transitoire (3 tentatives, backoff 5s/10s). S'arrête immédiatement sur les erreurs
     * non récupérables ({@code INVALID_ARGUMENT}, {@code Content too large}).
     *
     * @return le résultat, ou {@code null} si toutes les tentatives échouent.
     */
    private ContentPiiDetection analyzeWithRetry(String relPath, String text) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return piiDetectorClient.analyzeContent(text);
            } catch (RuntimeException t) {
                String msg = t.toString();
                if (msg.contains("INVALID_ARGUMENT") || msg.contains("Content too large")) {
                    log.warn("[scan] échec non récupérable sur {} : {}", relPath, msg);
                    return null;
                }
                if (attempt == maxAttempts) {
                    log.warn("[scan] échec sur {} après {} tentatives : {}", relPath, maxAttempts, msg);
                    return null;
                }
                long backoffMs = 5_000L * attempt;
                log.warn("[scan] tentative {}/{} échouée sur {} : {} — retry dans {}ms",
                    attempt, maxAttempts, relPath, msg, backoffMs);
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

    // ========================================================================
    // Progress tracker
    // ========================================================================

    /** Suivi de progression + ETA live pendant le scan complet. */
    private static final class ProgressTracker {
        private final long totalCharsToScan;
        private final int totalFiles;
        private final AtomicLong scannedChars = new AtomicLong();
        private final AtomicLong analyzedChars = new AtomicLong();
        private final AtomicLong totalAnalyzeMillis = new AtomicLong();
        private int doneFiles = 0;

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
            doneFiles++;
        }

        void logProgress(String currentFile) {
            long scanned = scannedChars.get();
            long analyzeMs = totalAnalyzeMillis.get();
            double pct = totalCharsToScan > 0 ? (100.0 * scanned / totalCharsToScan) : 0.0;
            double velocity = analyzeMs > 0 ? (analyzedChars.get() * 1000.0 / analyzeMs) : 0.0;
            long etaSec = velocity > 0 ? (long) (Math.max(0L, totalCharsToScan - scanned) / velocity) : -1;

            int filled = Math.min(30, Math.max(0, (int) (pct / 100.0 * 30)));
            StringBuilder bar = new StringBuilder(32).append('[');
            for (int i = 0; i < 30; i++) {
                bar.append(i < filled ? '#' : '.');
            }
            bar.append(']');

            log.info("[scan] [PROGRESS] {} {}/{} files {}/{} chars ({}%) {} chars/s ETA {} (last: {})",
                bar, doneFiles, totalFiles, scanned, totalCharsToScan,
                String.format(Locale.ROOT, "%.1f", pct),
                String.format(Locale.ROOT, "%.0f", velocity),
                formatDuration(etaSec), currentFile);
        }
    }

    // ========================================================================
    // Extraction / IO helpers
    // ========================================================================

    private String extractText(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".html") || name.endsWith(".htm")) {
                return htmlContentParser.cleanText(Files.readString(file, StandardCharsets.UTF_8));
            }
            return parseWithTika(file);
        } catch (Throwable t) {
            // Throwable (pas Exception) : certains sous-parsers Tika (.msg -> jsoup) lèvent
            // NoClassDefFoundError ; on ne veut pas avorter tout le run pour un fichier.
            log.debug("extractText failed for {}: {}", file, t.toString());
            return null;
        }
    }

    private String parseWithTika(Path file) throws Exception {
        // BodyContentHandler(-1) : pas de cap à 100k chars (défaut de Tika.parseToString),
        // sinon toute PII au-delà serait silencieusement masquée.
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        try (InputStream stream = Files.newInputStream(file)) {
            parser.parse(stream, handler, metadata, new ParseContext());
        }
        return handler.toString();
    }

    private static boolean isAttachment(Path file) {
        Path parent = file.getParent();
        if (parent == null) {
            return false;
        }
        if ("attachments".equals(parent.getFileName().toString())) {
            return true;
        }
        Path grandParent = parent.getParent();
        return grandParent != null && "attachments".equals(grandParent.getFileName().toString());
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
                 .filter(CorpusGliner2PresidioRegexScanIT::isScannableExtension)
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

    private void resetAndReseedDb() throws SQLException {
        jdbcTemplate.execute("DELETE FROM pii_type_config");
        jdbcTemplate.execute("DELETE FROM pii_detection_config");
        Resource resource = new DefaultResourceLoader().getResource(SQL_SEED);
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new EncodedResource(resource, StandardCharsets.UTF_8));
        }
        log.info("[seed] DB reseed depuis {}", SQL_SEED);
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

    // ========================================================================
    // Écriture findings + report
    // ========================================================================

    private void writeFindingLine(BufferedWriter writer, String piiTypeFolder, String pageFolder,
                                  PageMeta meta, String relPath, boolean attachment, String fullText,
                                  SensitiveData sd, boolean expectedHit) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("piiTypeFolder", piiTypeFolder);
        node.put("pageFolder", pageFolder);
        node.put("pageTitle", meta.title);
        node.put("pageUrl", meta.url);
        node.put("pageId", meta.pageId);
        node.put("relativePath", relPath);
        node.put("isAttachment", attachment);
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
        node.put("contextAfter", contextAfter(fullText, sd.end()));
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

    private static void writeReport(Path path, Stats stats, Duration dur) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Corpus scan report — GLINER2 + PRESIDIO + REGEX\n\n");
        sb.append("## Run metadata\n\n");
        sb.append("- SQL source: `").append(SQL_SEED).append("`\n");
        sb.append("- Run timestamp: ").append(Instant.now()).append("\n");
        sb.append("- Duration: ").append(dur.toSeconds()).append("s\n");
        sb.append("- Files scanned: ").append(stats.filesScanned).append("\n");
        sb.append("- Files skipped (unreadable/empty): ").append(stats.skippedFiles.size()).append("\n");
        sb.append("- Files failed (analyze error): ").append(stats.failedFiles.size()).append("\n");
        sb.append("- Files oversized (> ").append(MAX_TEXT_SIZE).append(" chars, rejected): ")
          .append(stats.oversizedFiles.size()).append("\n");
        sb.append("- Pages scanned: ").append(stats.pagesScanned).append("\n");
        sb.append("- **Total findings: ").append(stats.totalFindings).append("**\n\n");

        appendRecallSection(sb, stats);
        appendCountSection(sb, "Findings par PII type détecté", "PII Type", stats.countByPiiType, stats.totalFindings);
        appendCountSection(sb, "Findings par détecteur", "Detector", stats.countByDetector, stats.totalFindings);
        appendTopFilesSection(sb, stats);
        appendListSection(sb, "Files failed (analyze error)", stats.failedFiles);
        appendListSection(sb, "Files oversized (> " + MAX_TEXT_SIZE + " chars, rejected by detector)",
            stats.oversizedFiles);
        appendListSection(sb, "Files skipped (unreadable/empty)", stats.skippedFiles);

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void appendRecallSection(StringBuilder sb, Stats stats) {
        sb.append("## Recall par PII type attendu\n\n");
        sb.append("| PII type folder | Pages | Pages hit | Recall % | Codes acceptés |\n");
        sb.append("|---|---:|---:|---:|---|\n");
        EXPECTED_PII_TYPES.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                RecallCounter rc = stats.recallByPiiType.getOrDefault(entry.getKey(), new RecallCounter());
                sb.append("| ").append(entry.getKey())
                  .append(" | ").append(rc.totalPages)
                  .append(" | ").append(rc.hitPages)
                  .append(" | ").append(formatPct(rc.hitPages, rc.totalPages))
                  .append(" | ").append(String.join(", ", entry.getValue()))
                  .append(" |\n");
            });
        sb.append("\n");
    }

    private static void appendCountSection(StringBuilder sb, String title, String keyHeader,
                                           Map<String, Integer> counts, int total) {
        sb.append("## ").append(title).append("\n\n| ").append(keyHeader)
          .append(" | Count | % total |\n|---|---:|---:|\n");
        counts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .forEach(e -> sb.append("| ").append(e.getKey())
                .append(" | ").append(e.getValue())
                .append(" | ").append(formatPct(e.getValue(), total))
                .append(" |\n"));
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

    private static void appendListSection(StringBuilder sb, String title, List<String> items) {
        if (items.isEmpty()) {
            return;
        }
        sb.append("## ").append(title).append(" (").append(items.size()).append(")\n\n");
        items.forEach(f -> sb.append("- ").append(f).append("\n"));
        sb.append("\n");
    }

    private static String formatPct(int count, int total) {
        if (total == 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * count / total);
    }

    private static String formatDuration(long sec) {
        if (sec < 0) {
            return "n/a";
        }
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) {
            return String.format(Locale.ROOT, "%dh%02dm%02ds", h, m, s);
        }
        if (m > 0) {
            return String.format(Locale.ROOT, "%dm%02ds", m, s);
        }
        return s + "s";
    }

    // ========================================================================
    // Container plumbing
    // ========================================================================

    private static String ensureHfCacheDir() {
        try {
            Files.createDirectories(HF_CACHE_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create HF cache dir: " + HF_CACHE_DIR, e);
        }
        return HF_CACHE_DIR.toString();
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

    private static ImageFromDockerfile buildPiiDetectorImage() {
        Path repoRoot = Paths.get("..").toAbsolutePath().normalize();
        Path detectorRoot = repoRoot.resolve("pii-detector-service");
        return new ImageFromDockerfile("ai-sentinel-pii-detector-it", false)
            .withFileFromPath("pii-detector-service/Dockerfile",     detectorRoot.resolve("Dockerfile"))
            .withFileFromPath("pii-detector-service/pyproject.toml", detectorRoot.resolve("pyproject.toml"))
            .withFileFromPath("pii-detector-service/README.md",      detectorRoot.resolve("README.md"))
            .withFileFromPath("pii-detector-service/pii_detector",   detectorRoot.resolve("pii_detector"))
            .withFileFromPath("pii-detector-service/config",         detectorRoot.resolve("config"))
            .withFileFromPath("pii-detector-service/docker-entrypoint.sh",
                detectorRoot.resolve("docker-entrypoint.sh"))
            .withFileFromPath("proto", repoRoot.resolve("proto"))
            .withDockerfilePath("pii-detector-service/Dockerfile");
    }

    // ========================================================================
    // Value objects
    // ========================================================================

    private record PageMeta(String title, String url, String pageId) {
        static final PageMeta EMPTY = new PageMeta(null, null, null);
    }

    private static final class RecallCounter {
        int totalPages = 0;
        int hitPages = 0;
    }

    private static final class Stats {
        int filesScanned = 0;
        int pagesScanned = 0;
        int totalFindings = 0;
        final Map<String, Integer> countByPiiType = new TreeMap<>();
        final Map<String, Integer> countByDetector = new TreeMap<>();
        final Map<String, Integer> countByFile = new LinkedHashMap<>();
        final Map<String, RecallCounter> recallByPiiType = new TreeMap<>();
        final List<String> skippedFiles = new ArrayList<>();
        final List<String> failedFiles = new ArrayList<>();
        final List<String> oversizedFiles = new ArrayList<>();
    }
}
