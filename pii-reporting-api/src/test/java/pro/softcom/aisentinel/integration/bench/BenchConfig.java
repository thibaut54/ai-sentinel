package pro.softcom.aisentinel.integration.bench;

import java.util.LinkedHashSet;
import java.util.Set;

import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

/**
 * One evaluation configuration: the set of detectors enabled together.
 *
 * <p>The benchmark scores four isolated single-detector configs plus the full
 * union ("pipeline"). Per-detector metrics require isolation because the
 * cross-detector merge keeps a single (score-max) winner per overlapping span,
 * so the surviving finding's {@code source} cannot be trusted to attribute the
 * detection after a union scan.
 *
 * @param name      display/file-safe name
 * @param detectors detectors enabled together for this config
 */
public record BenchConfig(String name, Set<DetectorSource> detectors) {

    /** The four detectors evaluated in isolation (spec scope decision). */
    public static final Set<DetectorSource> PIPELINE_DETECTORS = Set.of(
        DetectorSource.GLINER2, DetectorSource.PRESIDIO,
        DetectorSource.REGEX, DetectorSource.OPENMED);

    public BenchConfig {
        detectors = new LinkedHashSet<>(detectors);
    }

    public static BenchConfig isolated(DetectorSource detector) {
        return new BenchConfig(detector.name(), Set.of(detector));
    }

    public static BenchConfig pipeline() {
        return new BenchConfig("PIPELINE", PIPELINE_DETECTORS);
    }
}
