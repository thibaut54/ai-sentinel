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
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Writes the side-by-side comparison of generative LLM extractors:
 * {@code target/pii-bench/extractor-comparison.{md,json,csv}}. Metrics are
 * value-level (see {@link ValueScorer}).
 */
final class ExtractorReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One model's evaluation over the gold set. */
    record ModelEval(
        String name,
        String model,
        ScoreResult score,
        int docsProcessed,
        int rawEntities,
        int droppedOutOfScope,
        int parseFailures,
        int httpFailures,
        Set<String> unknownLabels
    ) {
    }

    private ExtractorReport() {
    }

    static void write(Path outDir, List<ModelEval> evals, Map<String, Object> meta) throws IOException {
        Files.createDirectories(outDir);
        writeMarkdown(outDir.resolve("extractor-comparison.md"), evals, meta);
        writeJson(outDir.resolve("extractor-comparison.json"), evals, meta);
        writeCsv(outDir.resolve("extractor-comparison.csv"), evals);
    }

    private static void writeMarkdown(Path path, List<ModelEval> evals, Map<String, Object> meta)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# LLM PII extractor comparison (value-level)\n\n");
        sb.append("Metrics match on canonical concept + normalised value (models return values, not offsets). ")
          .append("Out-of-scope predictions (email, names, dates…) are dropped, not penalised.\n\n");
        sb.append("## Run metadata\n\n");
        meta.forEach((k, v) -> sb.append("- ").append(k).append(": `").append(v).append("`\n"));
        sb.append("- Generated: ").append(Instant.now()).append("\n\n");

        sb.append("## Summary (strict value-level)\n\n");
        sb.append("| Model | Docs | Raw ents | Dropped(OOS) | ParseFail | HTTPFail | TP | FP | FN | "
            + "Precision | Recall | F1 | Type-agnostic F1 | Typing errors |\n");
        sb.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ModelEval e : evals) {
            LabelCounts c = e.score().strictOverall();
            sb.append("| ").append(e.name())
              .append(" | ").append(e.docsProcessed())
              .append(" | ").append(e.rawEntities())
              .append(" | ").append(e.droppedOutOfScope())
              .append(" | ").append(e.parseFailures())
              .append(" | ").append(e.httpFailures())
              .append(" | ").append(c.tp())
              .append(" | ").append(c.fp())
              .append(" | ").append(c.fn())
              .append(" | ").append(pct(c.precision()))
              .append(" | ").append(pct(c.recall()))
              .append(" | ").append(pct(c.f1()))
              .append(" | ").append(pct(e.score().typeAgnosticOverall().f1()))
              .append(" | ").append(e.score().typingErrors())
              .append(" |\n");
        }
        sb.append("\n");

        if (evals.size() == 2) {
            LabelCounts a = evals.get(0).score().strictOverall();
            LabelCounts b = evals.get(1).score().strictOverall();
            sb.append("## Head-to-head Δ (").append(evals.get(1).name())
              .append(" − ").append(evals.get(0).name()).append(")\n\n");
            sb.append("| ΔPrecision | ΔRecall | ΔF1 |\n|---:|---:|---:|\n");
            sb.append("| ").append(delta(b.precision() - a.precision()))
              .append(" | ").append(delta(b.recall() - a.recall()))
              .append(" | ").append(delta(b.f1() - a.f1()))
              .append(" |\n\n");
        }

        for (ModelEval e : evals) {
            appendPerLabel(sb, e);
            if (!e.unknownLabels().isEmpty()) {
                sb.append("Unknown labels (extend label_mapping.toml [extractors]): `")
                  .append(String.join(", ", e.unknownLabels())).append("`\n\n");
            }
        }
        appendExamples(sb, evals);
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void appendPerLabel(StringBuilder sb, ModelEval e) {
        sb.append("### ").append(e.name()).append(" — per concept\n\n");
        sb.append("| Concept | Category | Support | TP | FP | FN | Precision | Recall | F1 |\n");
        sb.append("|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        e.score().strictByLabel().forEach((label, c) ->
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

    private static void appendExamples(StringBuilder sb, List<ModelEval> evals) {
        sb.append("## Examples (capped)\n\n");
        for (ModelEval e : evals) {
            sb.append("### ").append(e.name()).append("\n\n");
            sb.append("**False positives** (predicted, not gold):\n\n");
            e.score().falsePositives().stream().limit(10).forEach(x ->
                sb.append("- `").append(x.findingLabel()).append("` \"")
                  .append(escape(x.snippet())).append("\" (").append(x.docId()).append(")\n"));
            sb.append("\n**False negatives** (gold missed):\n\n");
            e.score().falseNegatives().stream().limit(10).forEach(x ->
                sb.append("- `").append(x.goldLabel()).append("` \"")
                  .append(escape(x.snippet())).append("\" (").append(x.docId()).append(")\n"));
            sb.append("\n");
        }
    }

    private static void writeJson(Path path, List<ModelEval> evals, Map<String, Object> meta)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode metaNode = root.putObject("metadata");
        meta.forEach((k, v) -> metaNode.put(k, String.valueOf(v)));
        ArrayNode models = root.putArray("models");
        for (ModelEval e : evals) {
            ObjectNode m = models.addObject();
            m.put("name", e.name());
            m.put("model", e.model());
            m.put("docsProcessed", e.docsProcessed());
            m.put("rawEntities", e.rawEntities());
            m.put("droppedOutOfScope", e.droppedOutOfScope());
            m.put("parseFailures", e.parseFailures());
            m.put("httpFailures", e.httpFailures());
            m.set("overallStrict", countsNode(e.score().strictOverall()));
            m.set("overallTypeAgnostic", countsNode(e.score().typeAgnosticOverall()));
            ObjectNode byLabel = m.putObject("byLabel");
            e.score().strictByLabel().forEach((label, c) -> byLabel.set(label, countsNode(c)));
            ArrayNode unknown = m.putArray("unknownLabels");
            e.unknownLabels().forEach(unknown::add);
        }
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root),
            StandardCharsets.UTF_8);
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

    private static void writeCsv(Path path, List<ModelEval> evals) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("model,scope,label,tp,fp,fn,precision,recall,f1");
            w.newLine();
            for (ModelEval e : evals) {
                csvRow(w, e.name(), "overall_strict", "ALL", e.score().strictOverall());
                csvRow(w, e.name(), "overall_type_agnostic", "ALL", e.score().typeAgnosticOverall());
                for (var en : e.score().strictByLabel().entrySet()) {
                    csvRow(w, e.name(), "label", en.getKey(), en.getValue());
                }
            }
        }
    }

    private static void csvRow(BufferedWriter w, String model, String scope, String label, LabelCounts c)
            throws IOException {
        w.write(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%d,%.4f,%.4f,%.4f",
            model, scope, label, c.tp(), c.fp(), c.fn(), c.precision(), c.recall(), c.f1()));
        w.newLine();
    }

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
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
