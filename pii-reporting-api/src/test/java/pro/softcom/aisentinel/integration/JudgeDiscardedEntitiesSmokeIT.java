package pro.softcom.aisentinel.integration;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
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
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import pro.softcom.aisentinel.AiSentinelApplication;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiDetectorClient;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection;
import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DiscardedSensitiveData;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

/**
 * Smoke test de bout en bout du canal {@code discarded_entities} (option C) :
 * vérifie que les entités écartées par le LLM-as-judge remontent dans la
 * réponse gRPC avec leur verdict, jusqu'au domaine Java
 * ({@link ContentPiiDetection#discardedByJudge()}).
 *
 * <p>Déterministe et sans dépendance à un vrai LLM : un <b>mock LM Studio</b>
 * ({@code llm-judge-mock/mock_lm_studio.py}) tourne dans un container sur le
 * même réseau et répond {@code FALSE_POSITIVE} à chaque audit. Le détecteur
 * (GLINER2 + PRESIDIO + REGEX, seed standard + flag {@code llm_judge_enabled}
 * forcé à {@code true}) écarte donc toutes les entités auditées : la réponse
 * doit avoir {@code sensitiveDataFound} vide et {@code discardedByJudge}
 * peuplé.
 *
 * <pre>mvn -Dtest=JudgeDiscardedEntitiesSmokeIT test</pre>
 */
@Testcontainers
@SpringBootTest(classes = AiSentinelApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class JudgeDiscardedEntitiesSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(JudgeDiscardedEntitiesSmokeIT.class);

    private static final String SQL_SEED = "classpath:sql/data-improved-gliner2-presidio-regex.sql";
    private static final int GRPC_PORT = 50051;
    private static final int MOCK_LLM_PORT = 1234;
    private static final String MOCK_LLM_ALIAS = "lm-studio-mock";

    private static final String POSTGRES_ALIAS = "postgres-it";
    private static final String DB_NAME = "ai-sentinel";
    private static final String DB_USER = "postgres";
    private static final String DB_PASSWORD = "postgres";

    /** Texte court contenant des PII sûres à détecter (IBAN + téléphone). */
    private static final String SAMPLE_TEXT =
        "Coordonnées bancaires du client : IBAN CH9300762011623852957, "
        + "téléphone +41 21 555 12 34, e-mail jean.dupont@example.ch.";

    private static final Path HF_CACHE_DIR = Paths.get(
        System.getProperty("corpus.bench.hf-cache",
            System.getProperty("user.home") + "/.ai-sentinel-it-hf-cache"));

    private static final Network NETWORK = Network.newNetwork();
    private static final Logger CONTAINER_LOG = LoggerFactory.getLogger("pii-detector-container");
    private static final Logger MOCK_LOG = LoggerFactory.getLogger("lm-studio-mock");

    @Container
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName(DB_NAME)
            .withUsername(DB_USER)
            .withPassword(DB_PASSWORD)
            .withNetwork(NETWORK)
            .withNetworkAliases(POSTGRES_ALIAS);

    /** Mock LM Studio : répond FALSE_POSITIVE à chaque audit du judge. */
    @Container
    static final GenericContainer<?> mockLmStudio = new GenericContainer<>("python:3.11-slim")
        .withCopyFileToContainer(
            MountableFile.forClasspathResource("llm-judge-mock/mock_lm_studio.py"),
            "/mock/mock_lm_studio.py")
        .withCommand("python", "/mock/mock_lm_studio.py")
        .withNetwork(NETWORK)
        .withNetworkAliases(MOCK_LLM_ALIAS)
        .withLogConsumer(frame -> MOCK_LOG.info(frame.getUtf8String().stripTrailing()))
        .waitingFor(Wait.forLogMessage(".*Mock LM Studio listening.*", 1))
        .withStartupTimeout(Duration.ofMinutes(2));

    @Container
    static final GenericContainer<?> piiDetector = new GenericContainer<>(buildPiiDetectorImage())
        .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(""))
        .withCommand("python", "-m", "pii_detector.server", "--port", String.valueOf(GRPC_PORT))
        .withExposedPorts(GRPC_PORT)
        .withNetwork(NETWORK)
        .withNetworkAliases("pii-detector")
        .withCreateContainerCmdModifier(cmd -> cmd.withHostConfig(
            new HostConfig().withBinds(new Bind(ensureHfCacheDir(), new Volume("/app/.cache/huggingface")))))
        .withEnv("HF_HOME", "/app/.cache/huggingface")
        .withEnv("TRANSFORMERS_CACHE", "/app/.cache/huggingface")
        .withEnv("DB_HOST", POSTGRES_ALIAS)
        .withEnv("DB_PORT", "5432")
        .withEnv("DB_NAME", DB_NAME)
        .withEnv("DB_USER", DB_USER)
        .withEnv("DB_PASSWORD", DB_PASSWORD)
        // Le judge doit auditer les trois détecteurs actifs du seed (le
        // défaut codé est {GLINER}, qui n'audite rien dans ce pipeline).
        .withEnv("LLM_JUDGE_AUDIT_SOURCES", "GLINER2,PRESIDIO,REGEX")
        // Pointe le judge vers le mock (priorité env > TOML).
        .withEnv("LLM_JUDGE_BASE_URL", "http://" + MOCK_LLM_ALIAS + ":" + MOCK_LLM_PORT + "/v1")
        .withLogConsumer(JudgeDiscardedEntitiesSmokeIT::routeContainerLog)
        .waitingFor(Wait.forLogMessage(".*Server started on port.*", 1))
        .withStartupTimeout(Duration.ofMinutes(10))
        .dependsOn(mockLmStudio);

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
        registry.add("pii-detector.connection-timeout-ms", () -> "600000");
        registry.add("pii-detector.request-timeout-ms",    () -> "600000");

        registry.add("PII_DATABASE_ENCRYPTION_KEY",
            () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        registry.add("PII_REPORTING_ALLOW_SECRET_REVEAL", () -> "false");
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private PiiDetectorClient piiDetectorClient;

    @Test
    void smokeJudgeDiscardedEntitiesEndToEnd() throws Exception {
        log.info("[judge-smoke] === START ===");
        resetAndReseedDbWithJudgeEnabled();

        ContentPiiDetection detection = piiDetectorClient.analyzeContent(SAMPLE_TEXT);

        log.info("[judge-smoke] kept={} discarded={}",
            detection.sensitiveDataFound().size(), detection.discardedByJudge().size());
        detection.discardedByJudge().forEach(d -> log.info(
            "[judge-smoke] [JUDGE-FP] type={} detector={} value={} verdict={} confidence={} reason={}",
            d.data().type(), d.data().source(), d.data().value(),
            d.judgeVerdict(), d.judgeConfidence(), d.judgeReason()));

        Assertions.assertFalse(detection.discardedByJudge().isEmpty(),
            "Le mock judge écarte tout : discardedByJudge ne doit pas être vide. "
            + "Si vide, le canal discarded_entities (proto/python/java) est cassé "
            + "ou le judge a tourné en fail-open (mock injoignable).");

        Assertions.assertTrue(detection.sensitiveDataFound().isEmpty(),
            "Toutes les entités sont auditées (GLINER2,PRESIDIO,REGEX) et le mock "
            + "rejette tout : sensitiveDataFound devrait être vide, trouvé : "
            + detection.sensitiveDataFound());

        for (DiscardedSensitiveData discarded : detection.discardedByJudge()) {
            Assertions.assertEquals("FALSE_POSITIVE", discarded.judgeVerdict(),
                "verdict inattendu pour " + discarded);
            Assertions.assertTrue(discarded.judgeReason().contains("mock judge"),
                "raison inattendue pour " + discarded);
            Assertions.assertTrue(discarded.judgeConfidence() > 0.9,
                "confidence inattendue pour " + discarded);
            Assertions.assertNotNull(discarded.data().source(),
                "detector source manquant pour " + discarded);
            Assertions.assertFalse(discarded.data().type().isBlank(),
                "pii type manquant pour " + discarded);
        }

        log.info("[judge-smoke] === DONE — canal discarded_entities opérationnel ===");
    }

    private void resetAndReseedDbWithJudgeEnabled() throws SQLException {
        jdbcTemplate.execute("DELETE FROM pii_type_config");
        jdbcTemplate.execute("DELETE FROM pii_detection_config");
        Resource resource = new DefaultResourceLoader().getResource(SQL_SEED);
        try (Connection conn = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new EncodedResource(resource, StandardCharsets.UTF_8));
        }
        jdbcTemplate.execute("UPDATE pii_detection_config SET llm_judge_enabled = true WHERE id = 1");
        log.info("[judge-smoke] DB reseed depuis {} + llm_judge_enabled=true", SQL_SEED);
    }

    // ========================================================================
    // Container plumbing (même approche que CorpusGliner2PresidioRegexScanIT)
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
            .withDockerfile(detectorRoot.resolve("Dockerfile"));
    }
}
