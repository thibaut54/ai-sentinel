package pro.softcom.aisentinel.integration.bench;

import java.util.Locale;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

/**
 * Turns a {@link BenchConfig} into the DB state needed for one evaluation, on
 * top of the already-reseeded master seed
 * ({@code data-improved-gliner2-presidio-regex.sql}, which carries the
 * {@code pii_type_config} rows of all four detectors).
 *
 * <p>It only flips the singleton {@code pii_detection_config} flags:
 * <ul>
 *   <li>{@code <det>_enabled} — true only for the config's detectors;</li>
 *   <li>{@code <det>_judge_enabled} — true for the config's detectors iff the
 *       judge is on (the global {@code llm_judge_enabled} gate is the OR);</li>
 *   <li>{@code prefilter_enabled = false} — so the only post-detection drops are
 *       the LLM-judge's, which lets a single judge-on scan yield both the
 *       judge-off set (kept ∪ discarded) and the judge-on set (kept).</li>
 * </ul>
 */
public final class DetectorConfigSeed {

    /** DetectorSource -> column prefix in pii_detection_config. */
    private static final Map<DetectorSource, String> COLUMN_PREFIX = Map.of(
        DetectorSource.GLINER, "gliner",
        DetectorSource.PRESIDIO, "presidio",
        DetectorSource.REGEX, "regex",
        DetectorSource.OPENMED, "openmed",
        DetectorSource.GLINER2, "gliner2");

    private DetectorConfigSeed() {
    }

    /**
     * Applies the flags for {@code config} with the judge always enabled for the
     * active detectors (the benchmark derives the judge-off metrics from the
     * discarded set of the same scan).
     *
     * @param thresholdOverride if non-null, sets {@code default_threshold} and the
     *                          per-type {@code threshold} of the active detectors
     *                          to this value (spec: thresholds parametrable)
     */
    public static void apply(JdbcTemplate jdbc, BenchConfig config, Double thresholdOverride) {
        StringBuilder sql = new StringBuilder("UPDATE pii_detection_config SET ");
        boolean first = true;
        boolean anyJudge = false;
        for (var entry : COLUMN_PREFIX.entrySet()) {
            boolean on = config.detectors().contains(entry.getKey());
            anyJudge = anyJudge || on;
            String prefix = entry.getValue();
            sql.append(first ? "" : ", ")
               .append(prefix).append("_enabled = ").append(on)
               .append(", ").append(prefix).append("_judge_enabled = ").append(on);
            first = false;
        }
        // Global judge gate is the OR maintained by the API; with the judge on
        // for every active detector it must be true or the validator never runs.
        sql.append(", llm_judge_enabled = ").append(anyJudge);
        sql.append(", prefilter_enabled = false");
        if (thresholdOverride != null) {
            sql.append(", default_threshold = ")
               .append(String.format(Locale.ROOT, "%.2f", thresholdOverride));
        }
        sql.append(" WHERE id = 1");
        jdbc.execute(sql.toString());

        if (thresholdOverride != null) {
            for (DetectorSource d : config.detectors()) {
                jdbc.update(
                    "UPDATE pii_type_config SET threshold = ? WHERE detector = ?",
                    thresholdOverride, d.name());
            }
        }
    }
}
