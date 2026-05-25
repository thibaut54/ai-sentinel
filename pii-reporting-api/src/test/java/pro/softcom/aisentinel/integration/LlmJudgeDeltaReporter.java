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
 * </ul>
 */
public final class LlmJudgeDeltaReporter {

    private static final Logger log = LoggerFactory.getLogger(LlmJudgeDeltaReporter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJudgeDeltaReporter() {
        // Utility class — no instantiation
    }

    /**
     * Reads two {@code findings.jsonl} files, computes delta metrics, and writes a Markdown report.
     *
     * <p>If the baseline or judged file does not exist, the report will note the missing file
     * rather than throwing an exception, so @Order(12) never fails the suite due to missing files
     * (the prior @Order(10/11) might have been skipped).
     *
     * @param baselineFindings path to the baseline {@code findings.jsonl}
     * @param judgedFindings   path to the judged {@code findings.jsonl}
     * @param outputMd         path where the Markdown delta report will be written
     * @throws IOException if the output directory cannot be created or the report cannot be written
     */
    public static void compareAndWrite(
            Path baselineFindings,
            Path judgedFindings,
            Path outputMd) throws IOException {

        log.info("[LLM-JUDGE-DELTA] Comparing baseline={} vs judged={}", baselineFindings, judgedFindings);

        List<JsonNode> baseline = readFindings(baselineFindings);
        List<JsonNode> judged = readFindings(judgedFindings);

        String report = buildReport(baselineFindings, judgedFindings, baseline, judged);

        Files.createDirectories(outputMd.getParent());
        Files.writeString(outputMd, report);
        log.info("[LLM-JUDGE-DELTA] Report written to {}", outputMd.toAbsolutePath());
    }

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

    private static String buildReport(
            Path baselinePath,
            Path judgedPath,
            List<JsonNode> baseline,
            List<JsonNode> judged) {

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

        // Global counts
        int baselineTotal = baseline.size();
        int judgedTotal = judged.size();
        int rejected = baselineTotal - judgedTotal;

        md.append("## Global Summary\n\n");
        md.append("| Metric | Value |\n");
        md.append("|---|---|\n");
        md.append("| Baseline findings | ").append(baselineTotal).append(" |\n");
        md.append("| Judged findings | ").append(judgedTotal).append(" |\n");
        md.append("| Rejected by judge | ").append(Math.max(0, rejected)).append(" |\n");

        double rejectionRate = baselineTotal > 0 ? (double) Math.max(0, rejected) / baselineTotal : 0.0;
        md.append("| Rejection rate | ").append(String.format("%.1f%%", rejectionRate * 100)).append(" |\n\n");

        // Precision: TP/(TP+FP) — computed from judgedKept/judgedRejected booleans if present
        long keptCount = judged.stream()
            .filter(n -> n.path("judgedKept").asBoolean(false))
            .count();
        long rejectedCount = baseline.stream()
            .filter(n -> n.path("judgedRejected").asBoolean(false))
            .count();

        if (keptCount > 0 || rejectedCount > 0) {
            md.append("## Judge Verdicts (from judgedKept/judgedRejected fields)\n\n");
            md.append("| Verdict | Count |\n");
            md.append("|---|---|\n");
            md.append("| Kept (TRUE_POSITIVE / UNSURE) | ").append(keptCount).append(" |\n");
            md.append("| Rejected (FALSE_POSITIVE) | ").append(rejectedCount).append(" |\n\n");
        }

        // Per-type breakdown
        md.append("## Findings by PII Type\n\n");
        md.append(buildPerTypeTable(baseline, judged));

        // Per-detector breakdown
        md.append("## Findings by Detector\n\n");
        md.append(buildPerDetectorTable(baseline, judged));

        // Recall preservation for non-GLINER detectors
        md.append("## Recall Preservation (Non-GLiNER Detectors)\n\n");
        md.append(buildRecallPreservationSection(baseline, judged));

        // Latency delta
        md.append("## Latency Delta\n\n");
        md.append(buildLatencySection(baseline, judged));

        return md.toString();
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
        // Non-GLINER detectors should not be affected by the judge (spec §2.5)
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
