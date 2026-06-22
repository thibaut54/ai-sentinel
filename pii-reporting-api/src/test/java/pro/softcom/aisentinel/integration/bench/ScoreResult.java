package pro.softcom.aisentinel.integration.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import pro.softcom.aisentinel.domain.pii.scan.ContentPiiDetection.DetectorSource;

/**
 * Aggregated span-level scoring for one (config, judge-state) evaluation.
 *
 * <p>Holds two views required by the spec:
 * <ul>
 *   <li><b>strict</b> — exact span boundaries AND matching canonical label;
 *       broken down overall, per label and per category;</li>
 *   <li><b>type-agnostic</b> — exact span boundaries, label ignored. The gap
 *       {@code typeAgnostic.tp - strict.tp} isolates typing errors from
 *       localisation errors.</li>
 * </ul>
 * False-positive / false-negative examples are collected (capped) to feed the
 * report's qualitative section.
 */
public final class ScoreResult {

    /** A captured FP or FN occurrence for the report. */
    public record Example(
        String docId,
        String dataset,
        int start,
        int end,
        String goldLabel,     // null for a false positive
        String findingLabel,  // null for a false negative
        DetectorSource source, // null for a false negative
        String snippet
    ) {
    }

    static final int EXAMPLE_CAP = 50;

    final LabelCounts strictOverall = new LabelCounts();
    final Map<String, LabelCounts> strictByLabel = new TreeMap<>();
    final Map<String, LabelCounts> strictByCategory = new TreeMap<>();
    final LabelCounts typeAgnosticOverall = new LabelCounts();

    final List<Example> falsePositives = new ArrayList<>();
    final List<Example> falseNegatives = new ArrayList<>();

    LabelCounts labelBucket(String label) {
        return strictByLabel.computeIfAbsent(label, k -> new LabelCounts());
    }

    LabelCounts categoryBucket(String label) {
        return strictByCategory.computeIfAbsent(CanonicalConcepts.categoryOf(label), k -> new LabelCounts());
    }

    void addFalsePositive(Example e) {
        if (falsePositives.size() < EXAMPLE_CAP) {
            falsePositives.add(e);
        }
    }

    void addFalseNegative(Example e) {
        if (falseNegatives.size() < EXAMPLE_CAP) {
            falseNegatives.add(e);
        }
    }

    public LabelCounts strictOverall() { return strictOverall; }
    public Map<String, LabelCounts> strictByLabel() { return strictByLabel; }
    public Map<String, LabelCounts> strictByCategory() { return strictByCategory; }
    public LabelCounts typeAgnosticOverall() { return typeAgnosticOverall; }
    public List<Example> falsePositives() { return falsePositives; }
    public List<Example> falseNegatives() { return falseNegatives; }

    /** Findings with a correct span but wrong canonical label (typing errors). */
    public int typingErrors() {
        return typeAgnosticOverall.tp() - strictOverall.tp();
    }
}
