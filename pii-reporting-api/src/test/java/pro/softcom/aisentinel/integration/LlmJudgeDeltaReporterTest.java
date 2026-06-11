package pro.softcom.aisentinel.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LlmJudgeDeltaReporter}.
 * Uses inline fixtures (JSONL strings) to verify report generation logic
 * without any Spring context or external dependencies.
 */
class LlmJudgeDeltaReporterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    // ========================== compareAndWrite happy path (existing 6 tests) ==========================

    @Test
    void Should_WriteMarkdownReport_When_GivenTwoFindingsJsonl() throws IOException {
        // Arrange
        Path baselineFile = tempDir.resolve("baseline/findings.jsonl");
        Path judgedFile = tempDir.resolve("judged/findings.jsonl");
        Path reportFile = tempDir.resolve("report/judge-delta.md");

        Files.createDirectories(baselineFile.getParent());
        Files.createDirectories(judgedFile.getParent());

        String baselineContent = """
            {"piiType":"PASSWORD","detector":"GLINER","score":0.85}
            {"piiType":"NATIONAL_ID","detector":"GLINER","score":0.78}
            {"piiType":"EMAIL_ADDRESS","detector":"REGEX","score":0.99}
            """;

        String judgedContent = """
            {"piiType":"EMAIL_ADDRESS","detector":"REGEX","score":0.99}
            """;

        Files.writeString(baselineFile, baselineContent);
        Files.writeString(judgedFile, judgedContent);

        // Act
        LlmJudgeDeltaReporter.DeltaSummary summary =
            LlmJudgeDeltaReporter.compareAndWrite(baselineFile, judgedFile, reportFile);

        // Assert
        assertThat(reportFile).exists();
        String report = Files.readString(reportFile);
        assertThat(report)
            .as("Report should contain the main heading")
            .contains("# LLM Judge Delta Report")
            .as("Report should contain global summary")
            .contains("## Global Summary")
            .as("Report should show baseline total of 3")
            .contains("| Baseline findings | 3 |")
            .as("Report should show judged total of 1")
            .contains("| Judged findings | 1 |");
        assertThat(summary).as("compareAndWrite must return a non-null DeltaSummary").isNotNull();
        assertThat(summary.baselineFindings()).as("Baseline total").isEqualTo(3L);
        assertThat(summary.judgedFindings()).as("Judged total").isEqualTo(1L);
    }

    @Test
    void Should_ComputePrecisionImprovement_When_JudgeRejectsFalsePositives() throws IOException {
        // Arrange — judge rejects 2 out of 3 GLiNER findings (the FPs), keeping 1 TP
        Path baselineFile = tempDir.resolve("baseline2/findings.jsonl");
        Path judgedFile = tempDir.resolve("judged2/findings.jsonl");
        Path reportFile = tempDir.resolve("report2/judge-delta.md");

        Files.createDirectories(baselineFile.getParent());
        Files.createDirectories(judgedFile.getParent());

        String baselineContent = """
            {"piiType":"PASSWORD","detector":"GLINER","judgedRejected":false}
            {"piiType":"NATIONAL_ID","detector":"GLINER","judgedRejected":true}
            {"piiType":"SSN","detector":"GLINER","judgedRejected":true}
            """;

        String judgedContent = """
            {"piiType":"PASSWORD","detector":"GLINER","judgedKept":true}
            """;

        Files.writeString(baselineFile, baselineContent);
        Files.writeString(judgedFile, judgedContent);

        // Act
        LlmJudgeDeltaReporter.compareAndWrite(baselineFile, judgedFile, reportFile);

        // Assert
        String report = Files.readString(reportFile);
        assertThat(report)
            .as("Report should contain rejection rate")
            .contains("| Rejected by judge |")
            .as("Report should reference judgedKept/judgedRejected section when fields present")
            .contains("Kept (TRUE_POSITIVE / UNSURE)");
    }

    @Test
    void Should_VerifyRecallPreservedForNonGlinerDetectors() throws IOException {
        // Arrange — REGEX and PRESIDIO counts must be identical between baseline and judged
        Path baselineFile = tempDir.resolve("baseline3/findings.jsonl");
        Path judgedFile = tempDir.resolve("judged3/findings.jsonl");
        Path reportFile = tempDir.resolve("report3/judge-delta.md");

        Files.createDirectories(baselineFile.getParent());
        Files.createDirectories(judgedFile.getParent());

        // Baseline: 2 GLINER + 1 REGEX + 1 PRESIDIO
        String baselineContent = """
            {"piiType":"PASSWORD","detector":"GLINER","score":0.85}
            {"piiType":"NATIONAL_ID","detector":"GLINER","score":0.80}
            {"piiType":"EMAIL_ADDRESS","detector":"REGEX","score":0.99}
            {"piiType":"CREDIT_CARD","detector":"PRESIDIO","score":0.95}
            """;

        // Judged: 0 GLINER (both rejected) + 1 REGEX + 1 PRESIDIO (unchanged)
        String judgedContent = """
            {"piiType":"EMAIL_ADDRESS","detector":"REGEX","score":0.99}
            {"piiType":"CREDIT_CARD","detector":"PRESIDIO","score":0.95}
            """;

        Files.writeString(baselineFile, baselineContent);
        Files.writeString(judgedFile, judgedContent);

        // Act
        LlmJudgeDeltaReporter.compareAndWrite(baselineFile, judgedFile, reportFile);

        // Assert
        String report = Files.readString(reportFile);
        assertThat(report)
            .as("Report should contain recall preservation section")
            .contains("## Recall Preservation (Non-GLiNER Detectors)")
            .as("REGEX recall should be marked as preserved")
            .contains("REGEX")
            .contains("YES")
            .as("PRESIDIO recall should be marked as preserved")
            .contains("PRESIDIO")
            .contains("YES");
    }

    // ========================== edge cases ==========================

    @Test
    void Should_WriteReportWithMissingNote_When_BothFilesAreMissing() throws IOException {
        // Arrange
        Path baselineFile = tempDir.resolve("nonexistent/baseline.jsonl");
        Path judgedFile = tempDir.resolve("nonexistent/judged.jsonl");
        Path reportFile = tempDir.resolve("output/judge-delta.md");

        // Act
        LlmJudgeDeltaReporter.compareAndWrite(baselineFile, judgedFile, reportFile);

        // Assert
        assertThat(reportFile).exists();
        String report = Files.readString(reportFile);
        assertThat(report)
            .as("Report should note that files are missing")
            .contains("Both files are empty or missing");
    }

    @Test
    void Should_HandleEmptyLines_When_JsonlContainsBlankLines() throws IOException {
        // Arrange
        Path baselineFile = tempDir.resolve("blanks/baseline.jsonl");
        Path judgedFile = tempDir.resolve("blanks/judged.jsonl");
        Path reportFile = tempDir.resolve("blanks/judge-delta.md");

        Files.createDirectories(baselineFile.getParent());

        String contentWithBlanks = """
            {"piiType":"EMAIL","detector":"REGEX"}

            {"piiType":"PHONE","detector":"REGEX"}

            """;

        Files.writeString(baselineFile, contentWithBlanks);
        Files.writeString(judgedFile, contentWithBlanks);

        // Act — should not throw on blank lines
        LlmJudgeDeltaReporter.compareAndWrite(baselineFile, judgedFile, reportFile);

        // Assert
        assertThat(reportFile).exists();
        String report = Files.readString(reportFile);
        assertThat(report)
            .as("Report should parse 2 findings ignoring blank lines")
            .contains("| Baseline findings | 2 |");
    }

    @Test
    void Should_WritePerTypeBreakdown_When_MultiplePiiTypesPresent() throws IOException {
        // Arrange
        Path baselineFile = tempDir.resolve("breakdown/baseline.jsonl");
        Path judgedFile = tempDir.resolve("breakdown/judged.jsonl");
        Path reportFile = tempDir.resolve("breakdown/judge-delta.md");

        Files.createDirectories(baselineFile.getParent());

        String baseline = """
            {"piiType":"PASSWORD","detector":"GLINER"}
            {"piiType":"NATIONAL_ID","detector":"GLINER"}
            {"piiType":"EMAIL","detector":"REGEX"}
            """;

        String judged = """
            {"piiType":"EMAIL","detector":"REGEX"}
            """;

        Files.writeString(baselineFile, baseline);
        Files.writeString(judgedFile, judged);

        // Act
        LlmJudgeDeltaReporter.compareAndWrite(baselineFile, judgedFile, reportFile);

        // Assert
        String report = Files.readString(reportFile);
        assertThat(report)
            .as("Report should include per-type breakdown section")
            .contains("## Findings by PII Type")
            .as("PASSWORD type should appear in breakdown")
            .contains("PASSWORD")
            .as("NATIONAL_ID type should appear in breakdown")
            .contains("NATIONAL_ID");
    }

    // ========================== DeltaSummary unit tests (new — spec §5) ==========================

    @Test
    void Should_ComputeBaselineFpRate_When_FindingsContainsRejections() {
        // Arrange — 3 GLiNER findings, 2 rejected by judge → proxy FP rate = 2/3 ≈ 0.667
        List<com.fasterxml.jackson.databind.JsonNode> baseline = List.of(
            finding("GLINER", false, true, false),   // judgedRejected=true
            finding("GLINER", false, true, false),   // judgedRejected=true
            finding("GLINER", false, false, false)   // judgedRejected=false
        );
        List<com.fasterxml.jackson.databind.JsonNode> judged = List.of(
            finding("GLINER", true, false, false)    // judgedKept=true
        );

        // Act
        LlmJudgeDeltaReporter.DeltaSummary summary =
            LlmJudgeDeltaReporter.computeSummary(baseline, judged);

        // Assert
        assertThat(summary.baselineFpRate())
            .as("Spec §5.1 — baseline FP rate must be computed as glinerRejected / glinerTotal = 2/3")
            .isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(summary.glinerRejectedCount())
            .as("Spec §5.1 — 2 GLiNER findings were rejected")
            .isEqualTo(2L);
        assertThat(summary.glinerKeptCount())
            .as("Spec §5.1 — 1 GLiNER finding was kept")
            .isEqualTo(1L);
    }

    @Test
    void Should_ComputeJudgedFpRateAsZero_When_AllRetainedAreTruePositives() {
        // Arrange — all judged findings have judgedKept=true → FP rate = 0 (best case)
        List<com.fasterxml.jackson.databind.JsonNode> baseline = List.of(
            finding("GLINER", false, false, false),
            finding("GLINER", false, false, false)
        );
        List<com.fasterxml.jackson.databind.JsonNode> judged = List.of(
            finding("GLINER", true, false, false),
            finding("GLINER", true, false, false)
        );

        // Act
        LlmJudgeDeltaReporter.DeltaSummary summary =
            LlmJudgeDeltaReporter.computeSummary(baseline, judged);

        // Assert
        assertThat(summary.judgedFpRate())
            .as("Spec §5.1 — judged FP rate must be 0.0 when all retained findings are judgedKept=true")
            .isEqualTo(0.0);
    }

    @Test
    void Should_FlagRecallPreserved_When_RegexAndPresidioCountsUnchanged() {
        // Arrange — REGEX and PRESIDIO counts identical in baseline and judged
        List<com.fasterxml.jackson.databind.JsonNode> baseline = List.of(
            findingWithDetector("REGEX"),
            findingWithDetector("REGEX"),
            findingWithDetector("PRESIDIO"),
            findingWithDetector("GLINER")
        );
        List<com.fasterxml.jackson.databind.JsonNode> judged = List.of(
            findingWithDetector("REGEX"),
            findingWithDetector("REGEX"),
            findingWithDetector("PRESIDIO")
            // GLINER removed by judge
        );

        // Act
        LlmJudgeDeltaReporter.DeltaSummary summary =
            LlmJudgeDeltaReporter.computeSummary(baseline, judged);

        // Assert
        assertThat(summary.recallPreservedRegex())
            .as("Spec §5.1 — REGEX recall must be preserved (same count baseline↔judged)")
            .isTrue();
        assertThat(summary.recallPreservedPresidio())
            .as("Spec §5.1 — PRESIDIO recall must be preserved (same count baseline↔judged)")
            .isTrue();
    }

    @Test
    void Should_FlagRecallNotPreserved_When_RegexCountDrops() {
        // Arrange — REGEX count drops from 2 to 1 in judged (regression)
        List<com.fasterxml.jackson.databind.JsonNode> baseline = List.of(
            findingWithDetector("REGEX"),
            findingWithDetector("REGEX"),
            findingWithDetector("PRESIDIO")
        );
        List<com.fasterxml.jackson.databind.JsonNode> judged = List.of(
            findingWithDetector("REGEX"),   // only 1 left — regression
            findingWithDetector("PRESIDIO")
        );

        // Act
        LlmJudgeDeltaReporter.DeltaSummary summary =
            LlmJudgeDeltaReporter.computeSummary(baseline, judged);

        // Assert
        assertThat(summary.recallPreservedRegex())
            .as("Spec §5.1 — REGEX recall must NOT be preserved when count drops from 2 to 1")
            .isFalse();
        assertThat(summary.recallPreservedPresidio())
            .as("Spec §5.1 — PRESIDIO recall must still be preserved")
            .isTrue();
    }

    @Test
    void Should_ComputeThroughputDelta_When_DurationsPresent() {
        // Arrange — 2 findings with durationMs set; total chars estimated from context
        List<com.fasterxml.jackson.databind.JsonNode> baseline = List.of(
            findingWithDuration(500L, "contextBefore1234", "contextAfter5678"),
            findingWithDuration(500L, "contextBefore1234", "contextAfter5678")
        );
        List<com.fasterxml.jackson.databind.JsonNode> judged = List.of(
            findingWithDuration(1500L, "contextBefore1234", "contextAfter5678")
        );

        // Act
        LlmJudgeDeltaReporter.DeltaSummary summary =
            LlmJudgeDeltaReporter.computeSummary(baseline, judged);

        // Assert
        assertThat(summary.baselineDurationS())
            .as("Spec §5.2 — baseline duration must be sum of durationMs in seconds = 1.0s")
            .isEqualTo(1.0);
        assertThat(summary.judgedDurationS())
            .as("Spec §5.2 — judged duration must be 1.5s")
            .isEqualTo(1.5);
        assertThat(summary.baselineThroughputCharsPerS())
            .as("Spec §5.2 — baseline throughput must be positive when duration > 0")
            .isGreaterThan(0.0);
    }

    @Test
    void Should_ReturnSentinelFpRate_When_GroundTruthMissing() {
        // Arrange — findings with no judgedRejected/judgedKept/isGroundTruthTruePositive fields
        List<com.fasterxml.jackson.databind.JsonNode> baseline = List.of(
            findingWithDetector("GLINER"),
            findingWithDetector("GLINER")
        );
        List<com.fasterxml.jackson.databind.JsonNode> judged = List.of(
            findingWithDetector("REGEX")
        );

        // Act
        LlmJudgeDeltaReporter.DeltaSummary summary =
            LlmJudgeDeltaReporter.computeSummary(baseline, judged);

        // Assert
        assertThat(summary.baselineFpRate())
            .as("Spec §5.1 — baselineFpRate must be NaN (sentinel) when ground truth is missing")
            .isNaN();
        assertThat(LlmJudgeDeltaReporter.FP_RATE_UNAVAILABLE)
            .as("Sentinel constant must be Double.NaN")
            .isNaN();
    }

    @Test
    void Should_AppendAcceptanceSectionToMarkdown_When_SeuilsViolated() throws IOException {
        // Arrange — judged FP rate > 15%, recall NOT preserved for REGEX
        Path baselineFile = tempDir.resolve("fail/baseline.jsonl");
        Path judgedFile = tempDir.resolve("fail/judged.jsonl");
        Path reportFile = tempDir.resolve("fail/report.md");

        Files.createDirectories(baselineFile.getParent());
        Files.createDirectories(judgedFile.getParent());

        // Baseline: 10 GLiNER, 0 rejected (no judgedRejected field) → FP rate = NaN
        // Judged: REGEX count drops (1 vs 2 in baseline) → recall NOT preserved
        String baselineContent = """
            {"piiType":"PASSWORD","detector":"REGEX","score":0.9}
            {"piiType":"NATIONAL_ID","detector":"REGEX","score":0.8}
            {"piiType":"EMAIL","detector":"GLINER","score":0.85}
            """;
        String judgedContent = """
            {"piiType":"PASSWORD","detector":"REGEX","score":0.9}
            {"piiType":"EMAIL","detector":"GLINER","judgedKept":false}
            """;

        Files.writeString(baselineFile, baselineContent);
        Files.writeString(judgedFile, judgedContent);

        // Act
        LlmJudgeDeltaReporter.compareAndWrite(baselineFile, judgedFile, reportFile);

        // Assert
        String report = Files.readString(reportFile);
        assertThat(report)
            .as("Spec §5 — report must contain the acceptance criteria section")
            .contains("## Acceptance Criteria (spec §5)")
            .as("Spec §5.1 — REGEX recall not preserved must produce FAIL verdict")
            .contains("**FAIL**");
    }

    @Test
    void Should_AppendAcceptanceSectionToMarkdown_When_SeuilsRespected() throws IOException {
        // Arrange — all seuils met: judgedKept for all findings (FP=0), recall preserved, throughput good
        Path baselineFile = tempDir.resolve("pass/baseline.jsonl");
        Path judgedFile = tempDir.resolve("pass/judged.jsonl");
        Path reportFile = tempDir.resolve("pass/report.md");

        Files.createDirectories(baselineFile.getParent());
        Files.createDirectories(judgedFile.getParent());

        String baselineContent = """
            {"piiType":"EMAIL","detector":"REGEX","durationMs":10}
            {"piiType":"CREDIT_CARD","detector":"PRESIDIO","durationMs":10}
            {"piiType":"PASSWORD","detector":"GLINER","judgedRejected":false,"durationMs":10}
            """;
        String judgedContent = """
            {"piiType":"EMAIL","detector":"REGEX","judgedKept":true,"durationMs":10}
            {"piiType":"CREDIT_CARD","detector":"PRESIDIO","judgedKept":true,"durationMs":10}
            {"piiType":"PASSWORD","detector":"GLINER","judgedKept":true,"durationMs":10}
            """;

        Files.writeString(baselineFile, baselineContent);
        Files.writeString(judgedFile, judgedContent);

        // Act
        LlmJudgeDeltaReporter.DeltaSummary summary =
            LlmJudgeDeltaReporter.compareAndWrite(baselineFile, judgedFile, reportFile);

        // Assert
        String report = Files.readString(reportFile);
        assertThat(report)
            .as("Spec §5 — report must contain the acceptance criteria section")
            .contains("## Acceptance Criteria (spec §5)");
        assertThat(summary.judgedFpRate())
            .as("Spec §5.1 — judged FP rate must be 0.0 when all kept")
            .isEqualTo(0.0);
        assertThat(summary.recallPreservedRegex())
            .as("Spec §5.1 — REGEX recall must be preserved")
            .isTrue();
        assertThat(summary.recallPreservedPresidio())
            .as("Spec §5.1 — PRESIDIO recall must be preserved")
            .isTrue();
    }

    // ============================== Fixture helpers ==============================

    /**
     * Builds a finding node with optional judgedKept/judgedRejected fields.
     *
     * @param detector       e.g. "GLINER", "REGEX"
     * @param judgedKept     value of the judgedKept field
     * @param judgedRejected value of the judgedRejected field
     * @param omitVerdicts   when true, neither judgedKept nor judgedRejected is added
     */
    private static com.fasterxml.jackson.databind.JsonNode finding(
            String detector,
            boolean judgedKept,
            boolean judgedRejected,
            boolean omitVerdicts) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("piiType", "PASSWORD");
        node.put("detector", detector);
        node.put("score", 0.85);
        if (!omitVerdicts) {
            node.put("judgedKept", judgedKept);
            node.put("judgedRejected", judgedRejected);
        }
        return node;
    }

    private static com.fasterxml.jackson.databind.JsonNode findingWithDetector(String detector) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("piiType", "PASSWORD");
        node.put("detector", detector);
        node.put("score", 0.85);
        return node;
    }

    private static com.fasterxml.jackson.databind.JsonNode findingWithDuration(
            long durationMs,
            String contextBefore,
            String contextAfter) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("piiType", "NATIONAL_ID");
        node.put("detector", "GLINER");
        node.put("durationMs", durationMs);
        node.put("contextBefore", contextBefore);
        node.put("contextAfter", contextAfter);
        return node;
    }
}
