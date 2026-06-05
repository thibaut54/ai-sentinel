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
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DiscardedSensitiveData;
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
import java.util.TreeSet;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Scanne le corpus Confluence hiérarchique ({@code src/test/resources/corpus/}) avec le
 * pipeline complet (backend Java + pii-detector Python + Postgres) en utilisant le seed
 * {@code data-improved-gliner2-presidio-regex.sql} (détecteurs actifs : GLINER2 + PRESIDIO
 * + REGEX ; GLiNER v1 et OpenMed désactivés ; LLM-judge activé par défaut, voir plus bas).
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
 * <p><b>LLM-as-judge</b> : par défaut le flag {@code llm_judge_enabled} est forcé à
 * {@code true} après le seed ({@code -Dcorpus.scan.judge=false} pour revenir au scan brut)
 * et le judge audite GLINER2 + PRESIDIO + REGEX. Chaque FP écarté par le judge remonte
 * dans la réponse gRPC ({@code discarded_entities}) avec son verdict : le test logge
 * chaque rejet (type de PII + détecteur), les journalise dans
 * {@code judge-discards.jsonl} (corrélés au fichier scanné) et agrège dans le report
 * l'efficacité du judge par PII type × détecteur (écartés vs gardés). Un pre-flight
 * vérifie que LM Studio est joignable depuis le container — sinon le judge tournerait
 * des heures en fail-open silencieux avec zéro rejet.
 *
 * <p>Sorties : {@code target/corpus-gliner2-presidio-regex/findings.jsonl} +
 * {@code judge-discards.jsonl} + {@code report.md}.
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
     * Nombre d'appels gRPC {@code DetectPII} simultanés côté client.
     *
     * <p>Le pipeline est dominé à ~99.7% par le forward pass GLiNER2 ; K requêtes en vol
     * alimentent les {@link #DETECTOR_WORKER_PROCESSES} processus d'inférence du serveur
     * (stratégie gagnante du bench, benchmarks/SCALING-CONCLUSIONS.md §7bis : ×4-5 vs
     * séquentiel avec le modèle privacy-filter). K légèrement supérieur au pool garde les
     * workers saturés (les requêtes excédentaires patientent dans la file gRPC).
     * Override : {@code -Dcorpus.scan.concurrency=N}. ⚠ Aligner sur l'allocation CPU de WSL2.
     */
    private static final int CLIENT_CONCURRENCY =
        Integer.getInteger("corpus.scan.concurrency", 12);

    /**
     * Threads PyTorch PAR requête. Avec K requêtes concurrentes sur le modèle partagé, le bon
     * réglage est {@code K x torch_threads <= CPU container} → 1 : chaque forward mono-thread,
     * K en parallèle. L'intra-op >1 thread scale très mal sur DeBERTa-large CPU (memory-bound :
     * t16 fait PIRE que t8) et oversouscrirait le CPU face à K flux.
     * Override : {@code -Dcorpus.scan.detector-torch-threads=N}.
     */
    private static final String DETECTOR_TORCH_THREADS =
        System.getProperty("corpus.scan.detector-torch-threads", "1");

    /**
     * Worker threads gRPC du serveur Python : pool de threads partageant le détecteur singleton.
     * Doit couvrir {@link #CLIENT_CONCURRENCY} avec une marge pour que chaque appel en vol ait
     * son thread (sinon les requêtes se sérialisent côté serveur).
     */
    private static final int DETECTOR_GRPC_WORKERS = Math.max(16, CLIENT_CONCURRENCY + 2);

    /**
     * Pool de PROCESSUS d'inférence du serveur ({@code PII_WORKER_PROCESSES}).
     *
     * <p>Défaut 8 = la stratégie gagnante du bench AVEC le modèle privacy-filter (1.23 Go) :
     * les N copies battent le modèle partagé multi-thread (587-732 vs ~580 c/s, le GIL
     * plafonnant les threads), et tiennent en RAM — en container Linux le pool est forké
     * APRÈS le chargement (copy-on-write : ~1 copie de poids pour N workers). 8 est le choix
     * sûr pour 14 CPU / 32 Go WSL ; monter à 12 si la RAM du container le permet. ⚠ Avec un
     * modèle LOURD (ex. gliner2-large-v1 1.86 Go, memory-bound), repasser à 0 : les copies
     * y sont PERDANTES (cf. SCALING-CONCLUSIONS.md §5.4 vs §7bis). Override :
     * {@code -Dcorpus.scan.detector-workers=N}.
     */
    private static final String DETECTOR_WORKER_PROCESSES =
        System.getProperty("corpus.scan.detector-workers", "8");

    /**
     * Active le LLM-as-judge in-pipeline (flag {@code llm_judge_enabled} forcé à
     * {@code true} après le seed) pour mesurer son efficacité anti-FP par PII type ×
     * détecteur. {@code -Dcorpus.scan.judge=false} restaure le scan brut historique.
     */
    private static final boolean JUDGE_ENABLED =
        Boolean.parseBoolean(System.getProperty("corpus.scan.judge", "true"));

    /**
     * Base URL LM Studio injectée dans le container ({@code LLM_JUDGE_BASE_URL}).
     * Vide par défaut : la valeur falsy laisse le validator Python retomber sur le
     * TOML ({@code [llm_judge].base_url}), source de vérité opérateur. Override :
     * {@code -Dcorpus.scan.judge-base-url=http://host:1234/v1}.
     */
    private static final String JUDGE_BASE_URL_OVERRIDE =
        System.getProperty("corpus.scan.judge-base-url", "");

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
        .withCommand("python", "-m", "pii_detector.server", "--port", String.valueOf(GRPC_PORT),
            "--workers", String.valueOf(DETECTOR_GRPC_WORKERS))
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
        // Data-parallélisme : N processus d'inférence × torch_threads chacun.
        // N x torch_threads doit rester <= CPU allouées au container.
        .withEnv("TORCH_NUM_THREADS", DETECTOR_TORCH_THREADS)
        .withEnv("PII_WORKER_PROCESSES", DETECTOR_WORKER_PROCESSES)
        // Portée du LLM-as-judge : le défaut codé est {GLINER}, qui n'auditerait
        // RIEN dans ce pipeline GLINER2+PRESIDIO+REGEX (passthrough silencieux).
        // Inerte quand le judge est désactivé (-Dcorpus.scan.judge=false).
        .withEnv("LLM_JUDGE_AUDIT_SOURCES", "GLINER2,PRESIDIO,REGEX")
        // Vide par défaut -> falsy côté Python -> fallback TOML (voir constante).
        .withEnv("LLM_JUDGE_BASE_URL", JUDGE_BASE_URL_OVERRIDE)
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
        assertJudgeReachable();
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
        assertJudgeReachable();
        warmupModels("[scan]");

        CorpusInventory inv = inventory();
        Velocity velocity = measureVelocity(inv);

        Path outDir = Paths.get(OUTPUT_ROOT).toAbsolutePath();
        Files.createDirectories(outDir);
        Path findingsPath = outDir.resolve("findings.jsonl");
        Path discardsPath = outDir.resolve("judge-discards.jsonl");
        Path processedPath = outDir.resolve("processed.txt");
        Path reportPath = outDir.resolve("report.md");

        // Reprise : si un run précédent a laissé des sorties, on saute les fichiers déjà traités
        // et on replie leurs findings dans les stats pour que le report final reste exact.
        Stats stats = new Stats();
        Set<String> doneFiles = new HashSet<>();
        Map<String, int[]> priorPageCounts = new HashMap<>();
        boolean resuming = Files.exists(processedPath)
            || (Files.exists(findingsPath) && Files.size(findingsPath) > 0);
        loadPriorRun(findingsPath, discardsPath, processedPath, stats, doneFiles, priorPageCounts);

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
             BufferedWriter discardsWriter = Files.newBufferedWriter(discardsPath, StandardCharsets.UTF_8, findingsOpts);
             BufferedWriter processedWriter = Files.newBufferedWriter(processedPath, StandardCharsets.UTF_8,
                 StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {

            ResumeState resume = new ResumeState(doneFiles, priorPageCounts, processedWriter);
            log.info("[scan] RESUME={} : {} fichiers déjà faits (sautés), {} findings antérieurs rechargés",
                resuming, doneFiles.size(), stats.totalFindings);

            List<ScanTask> tasks = buildScanTasks(corpusRoot, resume);
            log.info("[scan] {} fichiers à traiter, {} appels gRPC concurrents (torch_threads/worker={})",
                tasks.size(), CLIENT_CONCURRENCY, DETECTOR_TORCH_THREADS);

            // Comptes "expected/other" par page alimentés au fil des complétions (thread main
            // uniquement) ; fusionnés avec les comptes du run précédent dans finalizeRecall.
            Map<String, int[]> livePageCounts = new HashMap<>();
            executeTasksConcurrently(tasks, stats, findingsWriter, discardsWriter, progress,
                resume, livePageCounts);

            finalizeRecall(corpusRoot, stats, priorPageCounts, livePageCounts);
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

        // Échauffe ensuite les K flux concurrents (état BLAS/threads par worker serveur),
        // pour que la mesure de vitesse et le scan partent d'un serveur stabilisé.
        if (CLIENT_CONCURRENCY > 1) {
            Instant t1 = Instant.now();
            ExecutorService warmupPool = Executors.newFixedThreadPool(CLIENT_CONCURRENCY);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < CLIENT_CONCURRENCY; i++) {
                    futures.add(warmupPool.submit(() -> piiDetectorClient.analyzeContent(
                        "Warmup parallèle: IBAN CH9300762011623852957, tel +41 21 555 12 34.")));
                }
                for (Future<?> f : futures) {
                    f.get();
                }
                log.info("{} warmup concurrent x{} OK in {}s", tag, CLIENT_CONCURRENCY,
                    Duration.between(t1, Instant.now()).toSeconds());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Warmup interrompu", ie);
            } catch (ExecutionException ee) {
                throw new IllegalStateException("Warmup concurrent en échec", ee.getCause());
            } finally {
                warmupPool.shutdownNow();
            }
        }
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
        log.info("[scan] [ESTIMATE] temps estimé {} : pages {} + pièces jointes {} = TOTAL {} "
                + "(vitesse mesurée MONO-flux ; avec {} appels concurrents l'ETA réelle est "
                + "~/{} — la barre [PROGRESS] recale en continu sur la vitesse wall-clock réelle)",
            resuming ? "restant" : "",
            formatDuration(pageEtaSec), formatDuration(attEtaSec), formatDuration(totalEtaSec),
            CLIENT_CONCURRENCY, CLIENT_CONCURRENCY);
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
    // Scan concurrent du corpus
    // ========================================================================

    /** Unité de travail : un fichier à scanner, avec son contexte page/dossier. */
    private record ScanTask(String piiTypeFolder, String pageFolder, Set<String> expectedTypes,
                            PageMeta meta, Path file, String relPath, boolean attachment) {
        String pageKey() {
            return piiTypeFolder + "/" + pageFolder;
        }
    }

    private enum OutcomeStatus { SCANNED, SKIPPED_EMPTY, OVERSIZED, FAILED }

    /** Résultat d'un worker — consommé exclusivement par le thread principal. */
    private record ScanOutcome(ScanTask task, OutcomeStatus status, String text,
                               ContentPiiDetection detection, long analyzeMs) {}

    /** Construit la liste plate des fichiers restant à scanner (ordre hiérarchique stable). */
    private List<ScanTask> buildScanTasks(Path corpusRoot, ResumeState resume) throws IOException {
        List<ScanTask> tasks = new ArrayList<>();
        for (Path piiTypeDir : listDirectories(corpusRoot)) {
            String piiTypeFolder = piiTypeDir.getFileName().toString();
            Set<String> expectedTypes = EXPECTED_PII_TYPES.getOrDefault(piiTypeFolder, Set.of());
            for (Path pageDir : listDirectories(piiTypeDir)) {
                PageMeta meta = readMeta(pageDir.resolve("meta.json"));
                String pageFolder = pageDir.getFileName().toString();
                for (Path file : collectScannableFiles(pageDir)) {
                    String relPath = corpusRoot.relativize(file).toString().replace('\\', '/');
                    if (resume.doneFiles().contains(relPath)) {
                        continue; // déjà traité lors d'un run précédent — reprise
                    }
                    tasks.add(new ScanTask(piiTypeFolder, pageFolder, expectedTypes, meta,
                        file, relPath, isAttachment(file)));
                }
            }
        }
        return tasks;
    }

    /**
     * Exécute les tâches avec {@link #CLIENT_CONCURRENCY} appels gRPC en vol.
     *
     * <p>Concurrence maîtrisée :
     * <ul>
     *   <li>workers : extraction Tika + appel gRPC uniquement (aucun état partagé) ;</li>
     *   <li>thread principal : seul à toucher writers/stats/progress/resume (séquentiel) ;</li>
     *   <li>fenêtre glissante {@code K+2} pour borner la mémoire (textes en vol).</li>
     * </ul>
     */
    private void executeTasksConcurrently(List<ScanTask> tasks, Stats stats, BufferedWriter findingsWriter,
                                          BufferedWriter discardsWriter, ProgressTracker progress,
                                          ResumeState resume,
                                          Map<String, int[]> livePageCounts) throws IOException {
        ExecutorService executor = Executors.newFixedThreadPool(CLIENT_CONCURRENCY);
        CompletionService<ScanOutcome> completion = new ExecutorCompletionService<>(executor);
        try {
            int submitted = 0;
            int completed = 0;
            int inFlightCap = CLIENT_CONCURRENCY + 2;
            while (completed < tasks.size()) {
                while (submitted < tasks.size() && submitted - completed < inFlightCap) {
                    ScanTask task = tasks.get(submitted++);
                    completion.submit(() -> scanFile(task));
                }
                ScanOutcome outcome;
                try {
                    outcome = completion.take().get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Scan interrompu", ie);
                } catch (ExecutionException ee) {
                    throw new IllegalStateException("Worker de scan en échec inattendu", ee.getCause());
                }
                completed++;
                processOutcome(outcome, stats, findingsWriter, discardsWriter, progress,
                    resume, livePageCounts);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /** Côté worker : extraction + analyse, sans toucher au moindre état partagé. */
    private ScanOutcome scanFile(ScanTask task) {
        String text = extractText(task.file());
        if (text == null || text.isBlank()) {
            return new ScanOutcome(task, OutcomeStatus.SKIPPED_EMPTY, null, null, 0L);
        }
        if (text.length() > MAX_TEXT_SIZE) {
            // Rejeté d'office par le détecteur ("Content too large") — on évite le transfert.
            return new ScanOutcome(task, OutcomeStatus.OVERSIZED, text, null, 0L);
        }
        Instant t0 = Instant.now();
        ContentPiiDetection detection = analyzeWithRetry(task.relPath(), text);
        long analyzeMs = Duration.between(t0, Instant.now()).toMillis();
        if (detection == null) {
            return new ScanOutcome(task, OutcomeStatus.FAILED, text, null, analyzeMs);
        }
        return new ScanOutcome(task, OutcomeStatus.SCANNED, text, detection, analyzeMs);
    }

    /** Côté thread principal : écritures findings/processed/stats/progress (sérialisées). */
    private void processOutcome(ScanOutcome outcome, Stats stats, BufferedWriter findingsWriter,
                                BufferedWriter discardsWriter, ProgressTracker progress,
                                ResumeState resume,
                                Map<String, int[]> livePageCounts) throws IOException {
        ScanTask task = outcome.task();
        String relPath = task.relPath();
        switch (outcome.status()) {
            case SKIPPED_EMPTY -> {
                stats.skippedFiles.add(relPath);
                resume.markProcessed(relPath); // vide = déterministe, ne pas réessayer en reprise
            }
            case OVERSIZED -> {
                stats.oversizedFiles.add(relPath + " (" + outcome.text().length() + " chars)");
                resume.markProcessed(relPath); // déterministe — ne pas réessayer
            }
            case FAILED -> {
                // Échec d'analyse : NON marqué processed -> sera réessayé à la reprise.
                stats.failedFiles.add(relPath);
                progress.recordFile(outcome.text().length());
                progress.logProgress(relPath);
            }
            case SCANNED -> {
                stats.filesScanned++;
                progress.recordFile(outcome.text().length());
                progress.logProgress(relPath);

                int[] pageCounts = livePageCounts.computeIfAbsent(task.pageKey(), key -> new int[2]);
                for (SensitiveData sd : outcome.detection().sensitiveDataFound()) {
                    boolean expectedHit = task.expectedTypes().contains(sd.type());
                    writeFindingLine(findingsWriter, task.piiTypeFolder(), task.pageFolder(),
                        task.meta(), relPath, task.attachment(), outcome.text(), sd, expectedHit);
                    stats.totalFindings++;
                    stats.countByPiiType.merge(sd.type(), 1, Integer::sum);
                    stats.countByDetector.merge(detectorName(sd), 1, Integer::sum);
                    stats.countByFile.merge(relPath, 1, Integer::sum);
                    stats.keptByTypeDetector.merge(typeDetectorKey(sd.type(), detectorName(sd)),
                        1, Integer::sum);
                    pageCounts[expectedHit ? 0 : 1]++;
                }
                // FP écartés par le LLM-as-judge : log par rejet (type + détecteur),
                // journal corrélé au fichier, et agrégats type×détecteur.
                for (DiscardedSensitiveData discarded : outcome.detection().discardedByJudge()) {
                    SensitiveData sd = discarded.data();
                    log.info("[scan] [JUDGE-FP] type={} detector={} value=\"{}\" file={} reason=\"{}\"",
                        sd.type(), detectorName(sd), sd.value(), relPath, discarded.judgeReason());
                    writeDiscardLine(discardsWriter, task.piiTypeFolder(), task.pageFolder(),
                        task.meta(), relPath, task.attachment(), outcome.text(), discarded);
                    stats.totalDiscardedByJudge++;
                    stats.discardedByTypeDetector.merge(
                        typeDetectorKey(sd.type(), detectorName(sd)), 1, Integer::sum);
                }
                findingsWriter.flush(); // durable par fichier pour que la reprise garde les résultats
                discardsWriter.flush();
                resume.markProcessed(relPath);
            }
        }
    }

    /**
     * Recalcule le recall par page en fin de scan : une page est "hit" si elle a au moins un
     * finding attendu, qu'il vienne du run précédent ({@code priorPageCounts}) ou du live.
     * Équivalent au comptage incrémental de l'ancienne boucle séquentielle.
     */
    private void finalizeRecall(Path corpusRoot, Stats stats, Map<String, int[]> priorPageCounts,
                                Map<String, int[]> livePageCounts) throws IOException {
        for (Path piiTypeDir : listDirectories(corpusRoot)) {
            String piiTypeFolder = piiTypeDir.getFileName().toString();
            RecallCounter rc = stats.recallByPiiType.computeIfAbsent(piiTypeFolder, key -> new RecallCounter());
            for (Path pageDir : listDirectories(piiTypeDir)) {
                String pageKey = piiTypeFolder + "/" + pageDir.getFileName();
                rc.totalPages++;
                stats.pagesScanned++;
                if (expectedCountAt(priorPageCounts, pageKey) + expectedCountAt(livePageCounts, pageKey) > 0) {
                    rc.hitPages++;
                }
            }
        }
    }

    private static int expectedCountAt(Map<String, int[]> counts, String pageKey) {
        int[] c = counts.get(pageKey);
        return c != null ? c[0] : 0;
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
    private void loadPriorRun(Path findingsPath, Path discardsPath, Path processedPath, Stats stats,
                              Set<String> doneFiles, Map<String, int[]> priorPageCounts) throws IOException {
        if (Files.exists(processedPath)) {
            for (String line : Files.readAllLines(processedPath, StandardCharsets.UTF_8)) {
                String p = line.strip();
                if (!p.isEmpty()) {
                    doneFiles.add(p);
                }
            }
        }
        loadPriorDiscards(discardsPath, stats);
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
            if (type != null && detector != null) {
                stats.keptByTypeDetector.merge(typeDetectorKey(type, detector), 1, Integer::sum);
            }
            if (folder != null && page != null) {
                int[] counts = priorPageCounts.computeIfAbsent(folder + "/" + page, k -> new int[2]);
                counts[expectedHit ? 0 : 1]++;
            }
        }
        stats.filesScanned += filesWithFindings.size();
        log.info("[scan] loadPriorRun : {} findings antérieurs sur {} fichiers, {} FP-judge antérieurs, "
                + "{} fichiers dans le skip-set",
            stats.totalFindings, filesWithFindings.size(), stats.totalDiscardedByJudge, doneFiles.size());
    }

    /** Replie les FP écartés d'un run précédent ({@code judge-discards.jsonl}) dans les stats. */
    private void loadPriorDiscards(Path discardsPath, Stats stats) throws IOException {
        if (!Files.exists(discardsPath)) {
            return;
        }
        for (String line : Files.readAllLines(discardsPath, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode n = MAPPER.readTree(line);
            String type = textOrNull(n, "piiTypeDetected");
            String detector = textOrNull(n, "detector");
            stats.totalDiscardedByJudge++;
            if (type != null && detector != null) {
                stats.discardedByTypeDetector.merge(typeDetectorKey(type, detector), 1, Integer::sum);
            }
        }
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

    /**
     * Suivi de progression + ETA live pendant le scan complet.
     *
     * <p>La vitesse est calculée en <b>wall-clock</b> (chars scannés / temps écoulé depuis le
     * début du scan), pas en cumul des durées par fichier : avec K appels concurrents, le cumul
     * par fichier mesure la vitesse d'UN flux et gonflerait l'ETA d'un facteur ~K.
     */
    private static final class ProgressTracker {
        private final long totalCharsToScan;
        private final int totalFiles;
        private final Instant startedAt = Instant.now();
        private final AtomicLong scannedChars = new AtomicLong();
        private int doneFiles = 0;

        ProgressTracker(long totalCharsToScan, int totalFiles) {
            this.totalCharsToScan = totalCharsToScan;
            this.totalFiles = totalFiles;
        }

        void recordFile(int chars) {
            scannedChars.addAndGet(chars);
            doneFiles++;
        }

        void logProgress(String currentFile) {
            long scanned = scannedChars.get();
            long wallMs = Math.max(1L, Duration.between(startedAt, Instant.now()).toMillis());
            double pct = totalCharsToScan > 0 ? (100.0 * scanned / totalCharsToScan) : 0.0;
            double velocity = scanned * 1000.0 / wallMs;
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
        if (JUDGE_ENABLED) {
            jdbcTemplate.execute(
                "UPDATE pii_detection_config SET llm_judge_enabled = true WHERE id = 1");
        }
        log.info("[seed] DB reseed depuis {} (llm_judge_enabled={})", SQL_SEED, JUDGE_ENABLED);
    }

    /**
     * Pre-flight LM Studio : vérifie depuis le <b>container</b> (résolution env >
     * TOML identique à la prod) que l'endpoint du judge répond. Sans ce garde-fou,
     * un LM Studio injoignable ferait tourner tout le scan en fail-open silencieux :
     * zéro rejet, stats d'efficacité vides, des heures de CPU perdues.
     */
    private void assertJudgeReachable() {
        if (!JUDGE_ENABLED) {
            return;
        }
        String probe = "from pii_detector.infrastructure.validation.llm_validator import LLMJudgeValidator\n"
            + "import httpx\n"
            + "v = LLMJudgeValidator()\n"
            + "r = httpx.get(v.base_url.rstrip('/') + '/models', timeout=15)\n"
            + "r.raise_for_status()\n"
            + "print('JUDGE_OK', v.base_url, r.status_code)\n";
        try {
            var result = piiDetector.execInContainer("python", "-c", probe);
            log.info("[judge] pre-flight stdout: {}", result.getStdout().strip());
            Assertions.assertTrue(result.getStdout().contains("JUDGE_OK"),
                "LM Studio injoignable depuis le container pii-detector (le judge "
                + "tournerait en fail-open silencieux pendant tout le scan). stdout="
                + result.getStdout() + " stderr=" + result.getStderr()
                + " — démarrer LM Studio, ou -Dcorpus.scan.judge-base-url=... pour "
                + "pointer ailleurs, ou -Dcorpus.scan.judge=false pour scanner sans judge.");
        } catch (IOException e) {
            throw new IllegalStateException("Pre-flight LM Studio impossible", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Pre-flight LM Studio interrompu", e);
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

    // ========================================================================
    // Écriture findings + report
    // ========================================================================

    private void writeFindingLine(BufferedWriter writer, String piiTypeFolder, String pageFolder,
                                  PageMeta meta, String relPath, boolean attachment, String fullText,
                                  SensitiveData sd, boolean expectedHit) throws IOException {
        ObjectNode node = buildFindingNode(piiTypeFolder, pageFolder, meta, relPath, attachment,
            fullText, sd);
        node.put("expectedHit", expectedHit);
        writer.write(MAPPER.writeValueAsString(node));
        writer.newLine();
    }

    /**
     * Une ligne de {@code judge-discards.jsonl} : mêmes champs qu'un finding (corrélation
     * fichier/page conservée) + le verdict du LLM-as-judge qui a motivé le rejet.
     */
    private void writeDiscardLine(BufferedWriter writer, String piiTypeFolder, String pageFolder,
                                  PageMeta meta, String relPath, boolean attachment, String fullText,
                                  DiscardedSensitiveData discarded) throws IOException {
        ObjectNode node = buildFindingNode(piiTypeFolder, pageFolder, meta, relPath, attachment,
            fullText, discarded.data());
        node.put("judgeVerdict", discarded.judgeVerdict());
        if (discarded.judgeConfidence() != null) {
            node.put("judgeConfidence", discarded.judgeConfidence());
        } else {
            node.putNull("judgeConfidence");
        }
        node.put("judgeReason", discarded.judgeReason());
        writer.write(MAPPER.writeValueAsString(node));
        writer.newLine();
    }

    private ObjectNode buildFindingNode(String piiTypeFolder, String pageFolder, PageMeta meta,
                                        String relPath, boolean attachment, String fullText,
                                        SensitiveData sd) {
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
        return node;
    }

    /** Clé d'agrégation des comptes kept/écartés : {@code TYPE|DETECTOR}. */
    private static String typeDetectorKey(String type, String detector) {
        return type + "|" + detector;
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
        sb.append("- LLM judge enabled: ").append(JUDGE_ENABLED).append("\n");
        sb.append("- **Total findings (post-judge): ").append(stats.totalFindings).append("**\n");
        sb.append("- **FP écartés par le judge: ").append(stats.totalDiscardedByJudge).append("**\n\n");

        appendRecallSection(sb, stats);
        appendJudgeEfficiencySection(sb, stats);
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

    /**
     * Efficacité du LLM-as-judge : pour chaque couple (PII type, détecteur), le nombre de
     * findings bruts (gardés + écartés), les FP écartés et le taux d'écartement
     * ({@code écartés / bruts}). Deux agrégats (par type seul, par détecteur seul)
     * complètent la vue. Les "gardés" sont les findings finaux : l'amélioration du taux
     * de FP apportée par le judge se lit directement dans la colonne {@code % écartés}.
     */
    private static void appendJudgeEfficiencySection(StringBuilder sb, Stats stats) {
        sb.append("## Efficacité LLM-as-judge (FP écartés)\n\n");
        if (!JUDGE_ENABLED) {
            sb.append("_Judge désactivé (`-Dcorpus.scan.judge=false`) : aucune mesure._\n\n");
            return;
        }
        if (stats.totalDiscardedByJudge == 0 && stats.discardedByTypeDetector.isEmpty()) {
            sb.append("_Aucun FP écarté par le judge sur ce run._\n\n");
            return;
        }

        sb.append("### Par PII type × détecteur\n\n");
        sb.append("| PII type | Detector | Bruts | Écartés (FP) | Gardés | % écartés |\n");
        sb.append("|---|---|---:|---:|---:|---:|\n");
        Set<String> allKeys = new TreeSet<>(stats.keptByTypeDetector.keySet());
        allKeys.addAll(stats.discardedByTypeDetector.keySet());
        allKeys.stream()
            .sorted(Comparator.comparingInt(
                (String key) -> stats.discardedByTypeDetector.getOrDefault(key, 0)).reversed()
                .thenComparing(Comparator.naturalOrder()))
            .forEach(key -> {
                int kept = stats.keptByTypeDetector.getOrDefault(key, 0);
                int discarded = stats.discardedByTypeDetector.getOrDefault(key, 0);
                String[] parts = key.split("\\|", 2);
                sb.append("| ").append(parts[0])
                  .append(" | ").append(parts.length > 1 ? parts[1] : "?")
                  .append(" | ").append(kept + discarded)
                  .append(" | ").append(discarded)
                  .append(" | ").append(kept)
                  .append(" | ").append(formatPct(discarded, kept + discarded))
                  .append(" |\n");
            });
        sb.append("\n");

        appendJudgeAggregate(sb, "Par PII type", "PII type", stats, 0);
        appendJudgeAggregate(sb, "Par détecteur", "Detector", stats, 1);
    }

    /** Agrège kept/écartés sur un seul axe de la clé {@code TYPE|DETECTOR}. */
    private static void appendJudgeAggregate(StringBuilder sb, String title, String keyHeader,
                                             Stats stats, int keyPart) {
        Map<String, int[]> agg = new TreeMap<>();
        stats.keptByTypeDetector.forEach((key, count) ->
            agg.computeIfAbsent(key.split("\\|", 2)[keyPart], k -> new int[2])[0] += count);
        stats.discardedByTypeDetector.forEach((key, count) ->
            agg.computeIfAbsent(key.split("\\|", 2)[keyPart], k -> new int[2])[1] += count);

        sb.append("### ").append(title).append("\n\n");
        sb.append("| ").append(keyHeader).append(" | Bruts | Écartés (FP) | Gardés | % écartés |\n");
        sb.append("|---|---:|---:|---:|---:|\n");
        agg.entrySet().stream()
            .sorted(Map.Entry.<String, int[]>comparingByValue(
                Comparator.comparingInt(c -> -c[1])))
            .forEach(e -> {
                int kept = e.getValue()[0];
                int discarded = e.getValue()[1];
                sb.append("| ").append(e.getKey())
                  .append(" | ").append(kept + discarded)
                  .append(" | ").append(discarded)
                  .append(" | ").append(kept)
                  .append(" | ").append(formatPct(discarded, kept + discarded))
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
        int totalDiscardedByJudge = 0;
        final Map<String, Integer> countByPiiType = new TreeMap<>();
        final Map<String, Integer> countByDetector = new TreeMap<>();
        final Map<String, Integer> countByFile = new LinkedHashMap<>();
        /** Findings gardés (post-judge), clé {@code TYPE|DETECTOR}. */
        final Map<String, Integer> keptByTypeDetector = new TreeMap<>();
        /** FP écartés par le judge, clé {@code TYPE|DETECTOR}. */
        final Map<String, Integer> discardedByTypeDetector = new TreeMap<>();
        final Map<String, RecallCounter> recallByPiiType = new TreeMap<>();
        final List<String> skippedFiles = new ArrayList<>();
        final List<String> failedFiles = new ArrayList<>();
        final List<String> oversizedFiles = new ArrayList<>();
    }
}
