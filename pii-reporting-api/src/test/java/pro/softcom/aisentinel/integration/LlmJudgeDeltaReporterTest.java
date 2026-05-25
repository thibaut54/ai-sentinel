package pro.softcom.aisentinel.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LlmJudgeDeltaReporter}.
 * Uses inline fixtures (JSONL strings) to verify report generation logic
 * without any Spring context or external dependencies.
 */
class LlmJudgeDeltaReporterTest {

    @TempDir
    Path tempDir;

    // ========================== compareAndWrite happy path ==========================

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
        LlmJudgeDeltaReporter.compareAndWrite(baselineFile, judgedFile, reportFile);

        // Assert
        assertThat(reportFile).exists();
        String report = Files.readString(reportFile);
        assertThat(report)
            .as("Report should contain the main heading")
            .contains("# LLM Judge Delta Report");
        assertThat(report)
            .as("Report should contain global summary")
            .contains("## Global Summary");
        assertThat(report)
            .as("Report should show baseline total of 3")
            .contains("| Baseline findings | 3 |");
        assertThat(report)
            .as("Report should show judged total of 1")
            .contains("| Judged findings | 1 |");
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
            .contains("| Rejected by judge |");
        assertThat(report)
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
            .contains("## Recall Preservation (Non-GLiNER Detectors)");
        assertThat(report)
            .as("REGEX recall should be marked as preserved")
            .contains("REGEX")
            .contains("YES");
        assertThat(report)
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
            .contains("## Findings by PII Type");
        assertThat(report)
            .as("PASSWORD type should appear in breakdown")
            .contains("PASSWORD");
        assertThat(report)
            .as("NATIONAL_ID type should appear in breakdown")
            .contains("NATIONAL_ID");
    }
}
