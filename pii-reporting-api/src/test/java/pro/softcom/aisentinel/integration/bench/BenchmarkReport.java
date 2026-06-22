package pro.softcom.aisentinel.integration.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Writes the benchmark deliverables (spec §"Livrables"):
 * <ul>
 *   <li>{@code report.md}   — human-readable P/R/F1 tables + judge impact + FP/FN samples;</li>
 *   <li>{@code metrics.json}— full structured metrics (machine-readable);</li>
 *   <li>{@code metrics.csv} — flat rows for spreadsheets.</li>
 * </ul>
 */
public final class BenchmarkReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Per-config evaluation: judge-off vs judge-on scoring on the same scan. */
    public record ConfigEvaluation(BenchConfig config, ScoreResult judgeOff, ScoreResult judgeOn, int judgeDiscarded) {
    }

    /** Blind-spot concepts with no public-dataset coverage (spec §"Points d'attention"). */
    static final List<String> BLIND_SPOTS = List.of("ACCESS_TOKEN", "RECOVERY_CODE", "CARD_EXPIRY", "SECRET");

    private BenchmarkReport() {
    }

    public static void write(Path outDir, List<ConfigEvaluation> evals, Map<String, Object> meta) throws IOException {
        Files.createDirectories(outDir);
        writeMarkdown(outDir.resolve("report.md"), evals, meta);
        writeJson(outDir.resolve("metrics.json"), evals, meta);
        writeCsv(outDir.resolve("metrics.csv"), evals);
    }

    // ---------------------------------------------------------------- Markdown

    private static void writeMarkdown(Path path, List<ConfigEvaluation> evals, Map<String, Object> meta)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# PII detector benchmark — P/R/F1 per detector, pipeline & LLM-judge impact\n\n");
        sb.append("## Run metadata\n\n");
        meta.forEach((k, v) -> sb.append("- ").append(k).append(": `").append(v).append("`\n"));
        sb.append("- Generated: ").append(Instant.now()).append("\n\n");

        sb.append("## Summary — strict exact-match (span + canonical label)\n\n");
        sb.append("| Config | Judge | TP | FP | FN | Precision | Recall | F1 | Type-agnostic F1 | Typing errors | Judge drops |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ConfigEvaluation e : evals) {
            appendSummaryRow(sb, e.config().name(), "OFF", e.judgeOff(), 0);
            appendSummaryRow(sb, e.config().name(), "ON", e.judgeOn(), e.judgeDiscarded());
        }
        sb.append("\n");

        sb.append("## LLM-judge impact (ON − OFF)\n\n");
        sb.append("| Config | ΔPrecision | ΔRecall | ΔF1 | Drops |\n|---|---:|---:|---:|---:|\n");
        for (ConfigEvaluation e : evals) {
            LabelCounts off = e.judgeOff().strictOverall();
            LabelCounts on = e.judgeOn().strictOverall();
            sb.append("| ").append(e.config().name())
              .append(" | ").append(delta(on.precision() - off.precision()))
              .append(" | ").append(delta(on.recall() - off.recall()))
              .append(" | ").append(delta(on.f1() - off.f1()))
              .append(" | ").append(e.judgeDiscarded())
              .append(" |\n");
        }
        sb.append("\n");

        for (ConfigEvaluation e : evals) {
            appendPerLabelSection(sb, e);
        }

        appendBlindSpotsSection(sb, evals);
        appendExamplesSection(sb, evals);
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void appendSummaryRow(StringBuilder sb, String config, String judge, ScoreResult r, int drops) {
        LabelCounts c = r.strictOverall();
        sb.append("| ").append(config)
          .append(" | ").append(judge)
          .append(" | ").append(c.tp())
          .append(" | ").append(c.fp())
          .append(" | ").append(c.fn())
          .append(" | ").append(pct(c.precision()))
          .append(" | ").append(pct(c.recall()))
          .append(" | ").append(pct(c.f1()))
          .append(" | ").append(pct(r.typeAgnosticOverall().f1()))
          .append(" | ").append(r.typingErrors())
          .append(" | ").append(drops)
          .append(" |\n");
    }

    private static void appendPerLabelSection(StringBuilder sb, ConfigEvaluation e) {
        sb.append("### ").append(e.config().name()).append(" — per label (judge ON, strict)\n\n");
        sb.append("| Concept | Category | Support | TP | FP | FN | Precision | Recall | F1 |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        e.judgeOn().strictByLabel().forEach((label, c) ->
            sb.append("| ").append(label)
              .append(" | ").append(CanonicalConcepts.categoryOf(label))
              .append(" | ").append(c.support())
              .append(" | ").append(c.tp())
              .append(" | ").append(c.fp())
              .append(" | ").append(c.fn())
              .append(" | ").append(pct(c.precision()))
              .append(" | ").append(pct(c.recall()))
              .append(" | ").append(pct(c.f1()))
              .append(" |\n"));
        sb.append("\n");
    }

    private static void appendBlindSpotsSection(StringBuilder sb, List<ConfigEvaluation> evals) {
        sb.append("## Blind spots (synthetic-only / no public dataset gold)\n\n");
        sb.append("These concepts have no coverage in gretelai/ai4privacy and are measured only via synthetic ")
          .append("fixtures; absence of gold for them is NOT counted against recall.\n\n");
        sb.append("| Concept | Gold support (all configs) |\n|---|---:|\n");
        for (String concept : BLIND_SPOTS) {
            int support = evals.stream()
                .map(e -> e.judgeOn().strictByLabel().get(concept))
                .filter(c -> c != null)
                .mapToInt(LabelCounts::support)
                .max().orElse(0);
            sb.append("| ").append(concept).append(" | ").append(support).append(" |\n");
        }
        sb.append("\n");
    }

    private static void appendExamplesSection(StringBuilder sb, List<ConfigEvaluation> evals) {
        sb.append("## False-positive / false-negative examples (judge ON, capped)\n\n");
        for (ConfigEvaluation e : evals) {
            sb.append("### ").append(e.config().name()).append("\n\n");
            sb.append("**False positives** (detector fired, no gold):\n\n");
            e.judgeOn().falsePositives().stream().limit(10).forEach(x ->
                sb.append("- `").append(x.findingLabel()).append("` [").append(x.source()).append("] ")
                  .append('"').append(escape(x.snippet())).append("\" (").append(x.docId()).append(")\n"));
            sb.append("\n**False negatives** (gold missed):\n\n");
            e.judgeOn().falseNegatives().stream().limit(10).forEach(x ->
                sb.append("- `").append(x.goldLabel()).append("` ")
                  .append('"').append(escape(x.snippet())).append("\" (").append(x.docId()).append(")\n"));
            sb.append("\n");
        }
    }

    // -------------------------------------------------------------------- JSON

    private static void writeJson(Path path, List<ConfigEvaluation> evals, Map<String, Object> meta)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode metaNode = root.putObject("metadata");
        meta.forEach((k, v) -> metaNode.put(k, String.valueOf(v)));
        ArrayNode configs = root.putArray("configs");
        for (ConfigEvaluation e : evals) {
            ObjectNode cfg = configs.addObject();
            cfg.put("config", e.config().name());
            cfg.put("judgeDiscarded", e.judgeDiscarded());
            cfg.set("judgeOff", scoreNode(e.judgeOff()));
            cfg.set("judgeOn", scoreNode(e.judgeOn()));
        }
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
            StandardCharsets.UTF_8);
    }

    private static ObjectNode scoreNode(ScoreResult r) {
        ObjectNode node = MAPPER.createObjectNode();
        node.set("overallStrict", countsNode(r.strictOverall()));
        node.set("overallTypeAgnostic", countsNode(r.typeAgnosticOverall()));
        ObjectNode byLabel = node.putObject("byLabel");
        r.strictByLabel().forEach((label, c) -> byLabel.set(label, countsNode(c)));
        ObjectNode byCategory = node.putObject("byCategory");
        r.strictByCategory().forEach((cat, c) -> byCategory.set(cat, countsNode(c)));
        return node;
    }

    private static ObjectNode countsNode(LabelCounts c) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tp", c.tp());
        node.put("fp", c.fp());
        node.put("fn", c.fn());
        node.put("precision", round(c.precision()));
        node.put("recall", round(c.recall()));
        node.put("f1", round(c.f1()));
        return node;
    }

    // --------------------------------------------------------------------- CSV

    private static void writeCsv(Path path, List<ConfigEvaluation> evals) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("config,judge,scope,label,tp,fp,fn,precision,recall,f1");
            w.newLine();
            for (ConfigEvaluation e : evals) {
                csvScore(w, e.config().name(), "OFF", e.judgeOff());
                csvScore(w, e.config().name(), "ON", e.judgeOn());
            }
        }
    }

    private static void csvScore(BufferedWriter w, String config, String judge, ScoreResult r) throws IOException {
        csvRow(w, config, judge, "overall_strict", "ALL", r.strictOverall());
        csvRow(w, config, judge, "overall_type_agnostic", "ALL", r.typeAgnosticOverall());
        for (var en : r.strictByLabel().entrySet()) {
            csvRow(w, config, judge, "label", en.getKey(), en.getValue());
        }
        for (var en : r.strictByCategory().entrySet()) {
            csvRow(w, config, judge, "category", en.getKey(), en.getValue());
        }
    }

    private static void csvRow(BufferedWriter w, String config, String judge, String scope, String label,
                               LabelCounts c) throws IOException {
        w.write(String.format(Locale.ROOT, "%s,%s,%s,%s,%d,%d,%d,%.4f,%.4f,%.4f",
            config, judge, scope, label, c.tp(), c.fp(), c.fn(), c.precision(), c.recall(), c.f1()));
        w.newLine();
    }

    // ------------------------------------------------------------------ format

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * v);
    }

    private static String delta(double v) {
        return String.format(Locale.ROOT, "%+.1f pts", 100.0 * v);
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String escape(String s) {
        return s.replace("|", "\\|");
    }
}
