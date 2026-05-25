package pro.softcom.aisentinel.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility that compares two {@code findings.jsonl} files (baseline vs judged) and writes
 * a Markdown delta report with precision/recall metrics (spec §4.4, §4.2 @Order(12)).
 *
 * <p>Each line of a {@code findings.jsonl} file is a JSON object representing one finding.
 * Expected fields (best-effort, missing fields silently ignored):
 * <ul>
 *   <li>{@code piiType} — PII category (e.g. {@code PASSWORD}, {@code NATIONAL_ID})</li>
 *   <li>{@code detector} — Source detector (e.g. {@code GLINER}, {@code REGEX}, {@code PRESIDIO})</li>
 *   <li>{@code isGroundTruthTruePositive} — optional boolean for precision computation</li>
 *   <li>{@code judgedKept} — optional boolean: judge explicitly kept this finding</li>
 *   <li>{@code judgedRejected} — optional boolean: judge discarded this finding</li>
 *   <li>{@code durationMs} — optional long: gRPC call duration for latency delta</li>
 *   <li>{@code totalChars} — optional long: total chars scanned in the file</li>
 *   <li>{@code totalDurationMs} — optional long: total scan duration in ms</li>
 * </ul>
 */
public final class LlmJudgeDeltaReporter {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeDeltaReporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Sentinel value indicating ground truth is unavailable for FP rate computation. */
    public static final double FP_RATE_UNAVAILABLE = Double.NaN;

    private LlmJudgeDeltaReporter() {
        // Utility class — no instantiation
    }

    /**
     * Structured summary of the comparison between baseline and judged findings.
     *
     * <h3>FP Rate computation — ground truth caveat</h3>
     * <p>When no explicit ground truth ({@code isGroundTruthTruePositive} field) is available
     * in the {@code findings.jsonl}, the FP rate is computed as a proxy using the LLM judge
     * as an approximate oracle:
     * <pre>
     *   baselineFpRate  = glinerRejectedCount / baselineGlinerCount
     *   judgedFpRate    = 0.0  (all remaining findings were retained by the judge)
     * </pre>
     * <p>This proxy is valid only when the judge is well-calibrated (low miss rate).
     * For a production-grade FP rate, provide a human-annotated ground truth via
     * {@code isGroundTruthTruePositive} fields in the findings files.
     *
     * <p>When ground truth is unavailable AND the judge verdict fields ({@code judgedKept}/
     * {@code judgedRejected}) are absent, {@link #baselineFpRate()} and {@link #judgedFpRate()}
     * return {@link LlmJudgeDeltaReporter#FP_RATE_UNAVAILABLE} ({@code Double.NaN}).
     *
     * <h3>Throughput computation</h3>
     * <p>Throughput (chars/s) is derived from the {@code durationMs} field summed across all
     * findings and from the count of unique chars. When {@code durationMs} is absent from all
     * findings, throughput fields contain {@code 0.0}.
     *
     * @param baselineFindings         total findings in the baseline run
     * @param judgedFindings           total findings after judge filtering
     * @param baselineTruePositives    TP count on baseline (ground truth or proxy-estimated)
     * @param baselineFalsePositives   FP count on baseline (ground truth or proxy-estimated)
     * @param glinerRejectedCount      GLiNER entities rejected by the judge (verdict=FALSE_POSITIVE)
     * @param glinerKeptCount          GLiNER entities kept by the judge
     * @param baselineFpRate           FP / (TP+FP) on baseline; NaN if unavailable
     * @param judgedFpRate             FP / (TP+FP) after judge; NaN if unavailable
     * @param recallPreservedRegex     true if REGEX entity counts are identical baseline↔judged
     * @param recallPreservedPresidio  true if PRESIDIO entity counts are identical baseline↔judged
     * @param baselineDurationS        total scan duration on baseline (seconds); 0 if unavailable
     * @param judgedDurationS          total scan duration on judged run (seconds); 0 if unavailable
     * @param totalChars               total chars scanned across all corpus files
     * @param baselineThroughputCharsPerS baseline throughput in chars/s; 0 if unavailable
     * @param judgedThroughputCharsPerS   judged throughput in chars/s; 0 if unavailable
     */
    public record DeltaSummary(
        long baselineFindings,
        long judgedFindings,
        long baselineTruePositives,
        long baselineFalsePositives,
        long glinerRejectedCount,
        long glinerKeptCount,
        double baselineFpRate,
        double judgedFpRate,
        boolean recallPreservedRegex,
        boolean recallPreservedPresidio,
        double baselineDurationS,
        double judgedDurationS,
        long totalChars,
        double baselineThroughputCharsPerS,
        double judgedThroughputCharsPerS
    ) {}

    /**
     * Backward-compatible overload — reads, computes, and writes the report.
     * Callers that do not need the {@link DeltaSummary} can use this form.
     *
     * @param baselineFindings path to the baseline {@code findings.jsonl}
     * @param judgedFindings   path to the judged {@code findings.jsonl}
     * @param outputMd         path where the Markdown delta report will be written
     * @throws IOException if the output directory cannot be created or the report cannot be written
     */
    @SuppressWarnings("unused")
    public static void compareAndWriteVoid(
            Path baselineFindings,
            Path judgedFindings,
            Path outputMd) throws IOException {
        compareAndWrite(baselineFindings, judgedFindings, outputMd);
    }

    /**
     * Reads two {@code findings.jsonl} files, computes delta metrics, writes a Markdown report,
     * and returns a structured {@link DeltaSummary} for programmatic assertions (spec §5).
     *
     * <p>If the baseline or judged file does not exist, the report will note the missing file
     * rather than throwing an exception, so @Order(12) never fails the suite due to missing files
     * (the prior @Order(10/11) might have been skipped).
     *
     * @param baselineFindings path to the baseline {@code findings.jsonl}
     * @param judgedFindings   path to the judged {@code findings.jsonl}
     * @param outputMd         path where the Markdown delta report will be written
     * @return a {@link DeltaSummary} computed from the two files
     * @throws IOException if the output directory cannot be created or the report cannot be written
     */
    public static DeltaSummary compareAndWrite(
            Path baselineFindings,
            Path judgedFindings,
            Path outputMd) throws IOException {

        log.info("[LLM-JUDGE-DELTA] Comparing baseline={} vs judged={}", baselineFindings, judgedFindings);

        List<JsonNode> baseline = readFindings(baselineFindings);
        List<JsonNode> judged = readFindings(judgedFindings);

        DeltaSummary summary = computeSummary(baseline, judged);
        String report = buildReport(baselineFindings, judgedFindings, baseline, judged, summary);

        Files.createDirectories(outputMd.getParent());
        Files.writeString(outputMd, report);
        log.info("[LLM-JUDGE-DELTA] Report written to {}", outputMd.toAbsolutePath());

        return summary;
    }

    // ============================== DeltaSummary computation ==============================

    static DeltaSummary computeSummary(List<JsonNode> baseline, List<JsonNode> judged) {
        long baselineTotal = baseline.size();
        long judgedTotal = judged.size();

        // GLiNER-specific counts from baseline
        long baselineGlinerCount = baseline.stream()
            .filter(n -> "GLINER".equalsIgnoreCase(n.path("detector").asText("")))
            .count();
        long glinerRejectedCount = baseline.stream()
            .filter(n -> n.path("judgedRejected").asBoolean(false))
            .count();
        long glinerKeptCount = judged.stream()
            .filter(n -> n.path("judgedKept").asBoolean(false))
            .count();

        // FP rate computation — proxy when no ground truth available
        double baselineFpRate = computeBaselineFpRate(baseline, baselineGlinerCount, glinerRejectedCount);
        double judgedFpRate = computeJudgedFpRate(judged, judgedTotal);

        // Recall preservation for non-GLiNER detectors (spec §5.1, §2.5)
        boolean recallPreservedRegex = checkRecallPreserved(baseline, judged, "REGEX");
        boolean recallPreservedPresidio = checkRecallPreserved(baseline, judged, "PRESIDIO");

        // TP/FP on baseline (for reporting; proxy when GT absent)
        long baselineFp = Math.round(baselineFpRate == FP_RATE_UNAVAILABLE ? 0 :
            baselineFpRate * baselineTotal);
        long baselineTp = baselineTotal - baselineFp;

        // Duration and throughput
        double baselineDurationS = sumDurationSeconds(baseline);
        double judgedDurationS = sumDurationSeconds(judged);
        long totalChars = extractTotalChars(baseline);

        double baselineThroughput = computeThroughput(totalChars, baselineDurationS);
        double judgedThroughput = computeThroughput(totalChars, judgedDurationS);

        return new DeltaSummary(
            baselineTotal,
            judgedTotal,
            baselineTp,
            baselineFp,
            glinerRejectedCount,
            glinerKeptCount,
            baselineFpRate,
            judgedFpRate,
            recallPreservedRegex,
            recallPreservedPresidio,
            baselineDurationS,
            judgedDurationS,
            totalChars,
            baselineThroughput,
            judgedThroughput
        );
    }

    /**
     * Computes the baseline FP rate.
     *
     * <p>Priority:
     * <ol>
     *   <li>Ground truth ({@code isGroundTruthTruePositive} field present) → precise FP rate</li>
     *   <li>Judge verdicts ({@code judgedRejected} field) → proxy: rejectedGliner / totalGliner</li>
     *   <li>Neither → {@link #FP_RATE_UNAVAILABLE} (NaN)</li>
     * </ol>
     */
    static double computeBaselineFpRate(
            List<JsonNode> baseline,
            long baselineGlinerCount,
            long glinerRejectedCount) {

        boolean hasGroundTruth = baseline.stream().anyMatch(n -> n.has("isGroundTruthTruePositive"));
        if (hasGroundTruth) {
            long fp = baseline.stream()
                .filter(n -> n.has("isGroundTruthTruePositive")
                    && !n.path("isGroundTruthTruePositive").asBoolean(true))
                .count();
            long tp = baseline.stream()
                .filter(n -> n.path("isGroundTruthTruePositive").asBoolean(false))
                .count();
            long total = tp + fp;
            return total > 0 ? (double) fp / total : 0.0;
        }

        boolean hasJudgeVerdicts = baseline.stream().anyMatch(n -> n.has("judgedRejected"));
        if (hasJudgeVerdicts && baselineGlinerCount > 0) {
            // Proxy: judge-rejected GLiNER / total GLiNER
            return (double) glinerRejectedCount / baselineGlinerCount;
        }

        return FP_RATE_UNAVAILABLE;
    }

    /**
     * Computes the judged FP rate.
     *
     * <p>Priority:
     * <ol>
     *   <li>Ground truth present → precise FP rate on retained findings</li>
     *   <li>All retained findings have {@code judgedKept=true} → FP rate = 0.0 (best case)</li>
     *   <li>Neither → {@link #FP_RATE_UNAVAILABLE} (NaN)</li>
     * </ol>
     */
    static double computeJudgedFpRate(List<JsonNode> judged, long judgedTotal) {
        if (judgedTotal == 0) {
            return 0.0;
        }

        boolean hasGroundTruth = judged.stream().anyMatch(n -> n.has("isGroundTruthTruePositive"));
        if (hasGroundTruth) {
            long fp = judged.stream()
                .filter(n -> n.has("isGroundTruthTruePositive")
                    && !n.path("isGroundTruthTruePositive").asBoolean(true))
                .count();
            return (double) fp / judgedTotal;
        }

        boolean allKept = judged.stream().allMatch(n -> n.path("judgedKept").asBoolean(false));
        if (allKept) {
            return 0.0;
        }

        return FP_RATE_UNAVAILABLE;
    }

    private static boolean checkRecallPreserved(
            List<JsonNode> baseline,
            List<JsonNode> judged,
            String detector) {
        long baselineCount = baseline.stream()
            .filter(n -> detector.equalsIgnoreCase(n.path("detector").asText("")))
            .count();
        long judgedCount = judged.stream()
            .filter(n -> detector.equalsIgnoreCase(n.path("detector").asText("")))
            .count();
        return baselineCount == judgedCount;
    }

    private static double sumDurationSeconds(List<JsonNode> findings) {
        long totalMs = findings.stream()
            .filter(n -> n.has("durationMs"))
            .mapToLong(n -> n.path("durationMs").asLong(0L))
            .sum();
        return totalMs / 1000.0;
    }

    private static long extractTotalChars(List<JsonNode> findings) {
        // totalChars may be stored as a field on any finding line
        return findings.stream()
            .filter(n -> n.has("totalChars"))
            .mapToLong(n -> n.path("totalChars").asLong(0L))
            .findFirst()
            .orElseGet(() -> estimateTotalCharsFromContext(findings));
    }

    /**
     * Falls back to counting context chars as a rough estimate when no {@code totalChars} field exists.
     * Uses {@code contextBefore} and {@code contextAfter} fields length as proxy.
     */
    private static long estimateTotalCharsFromContext(List<JsonNode> findings) {
        return findings.stream()
            .mapToLong(n -> {
                String before = n.path("contextBefore").asText("");
                String after = n.path("contextAfter").asText("");
                return before.length() + after.length();
            })
            .sum();
    }

    private static double computeThroughput(long totalChars, double durationSeconds) {
        return durationSeconds > 0 ? totalChars / durationSeconds : 0.0;
    }

    // ============================== Markdown report ==============================

    private static String buildReport(
            Path baselinePath,
            Path judgedPath,
            List<JsonNode> baseline,
            List<JsonNode> judged,
            DeltaSummary summary) {

        StringBuilder md = new StringBuilder();
        md.append("# LLM Judge Delta Report\n\n");
        md.append("Comparison of baseline vs judge-filtered findings.\n\n");
        md.append("| | Value |\n");
        md.append("|---|---|\n");
        md.append("| Baseline file | `").append(baselinePath).append("` |\n");
        md.append("| Judged file | `").append(judgedPath).append("` |\n");
        md.append("| Baseline exists | ").append(Files.exists(baselinePath)).append(" |\n");
        md.append("| Judged exists | ").append(Files.exists(judgedPath)).append(" |\n\n");

        if (baseline.isEmpty() && judged.isEmpty()) {
            md.append("> Both files are empty or missing. "
                + "Run @Order(10) and @Order(11) first with LM Studio available.\n");
            return md.toString();
        }

        appendGlobalSummary(md, summary);
        appendJudgeVerdicts(md, summary);
        md.append("## Findings by PII Type\n\n");
        md.append(buildPerTypeTable(baseline, judged));
        md.append("## Findings by Detector\n\n");
        md.append(buildPerDetectorTable(baseline, judged));
        md.append("## Recall Preservation (Non-GLiNER Detectors)\n\n");
        md.append(buildRecallPreservationSection(baseline, judged));
        md.append("## Latency Delta\n\n");
        md.append(buildLatencySection(baseline, judged));
        md.append(buildAcceptanceCriteriaSection(summary));

        return md.toString();
    }

    private static void appendGlobalSummary(StringBuilder md, DeltaSummary summary) {
        long rejected = Math.max(0L, summary.baselineFindings() - summary.judgedFindings());
        double rejectionRate = summary.baselineFindings() > 0
            ? (double) rejected / summary.baselineFindings()
            : 0.0;

        md.append("## Global Summary\n\n");
        md.append("| Metric | Value |\n");
        md.append("|---|---|\n");
        md.append("| Baseline findings | ").append(summary.baselineFindings()).append(" |\n");
        md.append("| Judged findings | ").append(summary.judgedFindings()).append(" |\n");
        md.append("| Rejected by judge | ").append(rejected).append(" |\n");
        md.append("| Rejection rate | ").append(String.format("%.1f%%", rejectionRate * 100)).append(" |\n");

        appendFpRateRow(md, "Baseline FP rate (proxy)", summary.baselineFpRate());
        appendFpRateRow(md, "Judged FP rate", summary.judgedFpRate());
        md.append("\n");
    }

    private static void appendFpRateRow(StringBuilder md, String label, double rate) {
        if (Double.isNaN(rate)) {
            md.append("| ").append(label).append(" | N/A (no ground truth) |\n");
        } else {
            md.append("| ").append(label).append(" | ")
                .append(String.format("%.1f%%", rate * 100)).append(" |\n");
        }
    }

    private static void appendJudgeVerdicts(StringBuilder md, DeltaSummary summary) {
        if (summary.glinerKeptCount() > 0 || summary.glinerRejectedCount() > 0) {
            md.append("## Judge Verdicts (from judgedKept/judgedRejected fields)\n\n");
            md.append("| Verdict | Count |\n");
            md.append("|---|---|\n");
            md.append("| Kept (TRUE_POSITIVE / UNSURE) | ").append(summary.glinerKeptCount()).append(" |\n");
            md.append("| Rejected (FALSE_POSITIVE) | ").append(summary.glinerRejectedCount()).append(" |\n\n");
        }
    }

    /**
     * Appends the acceptance criteria section (spec §5) with PASS/FAIL verdicts.
     */
    static String buildAcceptanceCriteriaSection(DeltaSummary summary) {
        StringBuilder section = new StringBuilder();
        section.append("## Acceptance Criteria (spec §5)\n\n");
        section.append("| Criterion | Threshold | Actual | Verdict |\n");
        section.append("|---|---|---|---|\n");

        // §5.1 FP rate
        appendCriterionRow(section,
            "§5.1 Judged FP rate",
            "< 15%",
            formatRate(summary.judgedFpRate()),
            !Double.isNaN(summary.judgedFpRate()) && summary.judgedFpRate() < 0.15);

        // §5.1 Recall Regex
        appendCriterionRow(section,
            "§5.1 Recall preserved (REGEX)",
            "true",
            String.valueOf(summary.recallPreservedRegex()),
            summary.recallPreservedRegex());

        // §5.1 Recall Presidio
        appendCriterionRow(section,
            "§5.1 Recall preserved (PRESIDIO)",
            "true",
            String.valueOf(summary.recallPreservedPresidio()),
            summary.recallPreservedPresidio());

        // §5.2 Throughput minimum
        appendCriterionRow(section,
            "§5.2 Judged throughput",
            ">= 2100 chars/s",
            String.format("%.0f chars/s", summary.judgedThroughputCharsPerS()),
            summary.judgedThroughputCharsPerS() >= 2100.0);

        // §5.2 Throughput loss
        double throughputLossRatio = summary.baselineThroughputCharsPerS() > 0
            ? 1.0 - (summary.judgedThroughputCharsPerS() / summary.baselineThroughputCharsPerS())
            : 0.0;
        appendCriterionRow(section,
            "§5.2 Throughput loss",
            "< 50%",
            String.format("%.1f%%", throughputLossRatio * 100),
            throughputLossRatio < 0.50);

        return section.append("\n").toString();
    }

    private static void appendCriterionRow(
            StringBuilder section,
            String criterion,
            String threshold,
            String actual,
            boolean pass) {
        section.append("| ").append(criterion)
            .append(" | ").append(threshold)
            .append(" | ").append(actual)
            .append(" | ").append(pass ? "PASS" : "**FAIL**")
            .append(" |\n");
    }

    private static String formatRate(double rate) {
        return Double.isNaN(rate) ? "N/A" : String.format("%.1f%%", rate * 100);
    }

    private static String buildPerTypeTable(List<JsonNode> baseline, List<JsonNode> judged) {
        Map<String, Long> baselineByType = countBy(baseline, "piiType");
        Map<String, Long> judgedByType = countBy(judged, "piiType");

        StringBuilder table = new StringBuilder();
        table.append("| PII Type | Baseline | After Judge | Δ |\n");
        table.append("|---|---|---|---|\n");

        baselineByType.forEach((type, baseCount) -> {
            long judgedCount = judgedByType.getOrDefault(type, 0L);
            long delta = judgedCount - baseCount;
            table.append("| ").append(type)
                .append(" | ").append(baseCount)
                .append(" | ").append(judgedCount)
                .append(" | ").append(delta > 0 ? "+" : "").append(delta)
                .append(" |\n");
        });

        judgedByType.forEach((type, judgedCount) -> {
            if (!baselineByType.containsKey(type)) {
                table.append("| ").append(type)
                    .append(" | 0")
                    .append(" | ").append(judgedCount)
                    .append(" | +").append(judgedCount)
                    .append(" |\n");
            }
        });

        return table.append("\n").toString();
    }

    private static String buildPerDetectorTable(List<JsonNode> baseline, List<JsonNode> judged) {
        Map<String, Long> baselineByDetector = countBy(baseline, "detector");
        Map<String, Long> judgedByDetector = countBy(judged, "detector");

        StringBuilder table = new StringBuilder();
        table.append("| Detector | Baseline | After Judge | Δ |\n");
        table.append("|---|---|---|---|\n");

        baselineByDetector.forEach((detector, baseCount) -> {
            long judgedCount = judgedByDetector.getOrDefault(detector, 0L);
            long delta = judgedCount - baseCount;
            table.append("| ").append(detector)
                .append(" | ").append(baseCount)
                .append(" | ").append(judgedCount)
                .append(" | ").append(delta > 0 ? "+" : "").append(delta)
                .append(" |\n");
        });

        return table.append("\n").toString();
    }

    private static String buildRecallPreservationSection(List<JsonNode> baseline, List<JsonNode> judged) {
        List<String> nonGlinerDetectors = List.of("REGEX", "PRESIDIO", "OPENMED");

        StringBuilder section = new StringBuilder();
        section.append("> The LLM judge only audits GLiNER entities (spec §2.5). ")
            .append("Non-GLiNER detectors must show identical counts.\n\n");
        section.append("| Detector | Baseline | After Judge | Recall Preserved |\n");
        section.append("|---|---|---|---|\n");

        for (String detector : nonGlinerDetectors) {
            long baseCount = baseline.stream()
                .filter(n -> detector.equalsIgnoreCase(n.path("detector").asText("")))
                .count();
            long judgedCount = judged.stream()
                .filter(n -> detector.equalsIgnoreCase(n.path("detector").asText("")))
                .count();
            boolean preserved = baseCount == judgedCount;
            section.append("| ").append(detector)
                .append(" | ").append(baseCount)
                .append(" | ").append(judgedCount)
                .append(" | ").append(preserved ? "YES" : "**NO — REGRESSION**")
                .append(" |\n");
        }

        return section.append("\n").toString();
    }

    private static String buildLatencySection(List<JsonNode> baseline, List<JsonNode> judged) {
        OptionalStats baselineStats = computeLatencyStats(baseline);
        OptionalStats judgedStats = computeLatencyStats(judged);

        StringBuilder section = new StringBuilder();
        section.append("| Metric | Baseline | After Judge |\n");
        section.append("|---|---|---|\n");
        section.append("| Files with latency data | ")
            .append(baselineStats.count).append(" | ").append(judgedStats.count).append(" |\n");

        if (baselineStats.count > 0 && judgedStats.count > 0) {
            section.append("| Avg latency ms | ")
                .append(String.format("%.0f", baselineStats.avg))
                .append(" | ")
                .append(String.format("%.0f", judgedStats.avg))
                .append(" |\n");
            double overhead = judgedStats.avg - baselineStats.avg;
            section.append("| Judge overhead ms | N/A | ")
                .append(String.format("%+.0f", overhead)).append(" |\n");
        } else {
            section.append("| Note | No durationMs fields found in jsonl files | |\n");
        }

        return section.append("\n").toString();
    }

    // ============================== Internal helpers ==============================

    private static List<JsonNode> readFindings(Path path) {
        if (!Files.exists(path)) {
            log.warn("[LLM-JUDGE-DELTA] findings file not found: {}", path);
            return List.of();
        }
        try {
            List<JsonNode> result = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    result.add(MAPPER.readTree(trimmed));
                }
            }
            return result;
        } catch (IOException e) {
            log.error("[LLM-JUDGE-DELTA] Failed to read {}: {}", path, e.getMessage());
            return List.of();
        }
    }

    private static Map<String, Long> countBy(List<JsonNode> findings, String field) {
        return findings.stream()
            .collect(Collectors.groupingBy(
                n -> n.path(field).asText("UNKNOWN"),
                LinkedHashMap::new,
                Collectors.counting()
            ));
    }

    private static OptionalStats computeLatencyStats(List<JsonNode> findings) {
        List<Long> durations = findings.stream()
            .filter(n -> n.has("durationMs"))
            .map(n -> n.path("durationMs").asLong(0L))
            .toList();

        if (durations.isEmpty()) {
            return new OptionalStats(0, 0.0);
        }
        double avg = durations.stream().mapToLong(Long::longValue).average().orElse(0.0);
        return new OptionalStats(durations.size(), avg);
    }

    private record OptionalStats(int count, double avg) {}
}
