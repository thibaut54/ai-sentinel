package pro.softcom.aisentinel.integration.bench;

/**
 * Mutable confusion-matrix accumulator for a single label (or an aggregate).
 *
 * <p>Span-level counts: {@code tp} = gold span matched by a finding,
 * {@code fp} = finding matching no gold span (and not on an ignore zone),
 * {@code fn} = gold span no finding matched. Precision/recall/F1 follow the
 * standard definitions; a 0/0 ratio is reported as 0.0 (not NaN) so empty
 * labels render cleanly in the report.
 */
public final class LabelCounts {

    int tp;
    int fp;
    int fn;

    void addTp() { tp++; }
    void addFp() { fp++; }
    void addFn() { fn++; }

    void add(LabelCounts other) {
        this.tp += other.tp;
        this.fp += other.fp;
        this.fn += other.fn;
    }

    public int tp() { return tp; }
    public int fp() { return fp; }
    public int fn() { return fn; }

    /** Gold positives = tp + fn. */
    public int support() { return tp + fn; }

    public double precision() {
        int denom = tp + fp;
        return denom == 0 ? 0.0 : (double) tp / denom;
    }

    public double recall() {
        int denom = tp + fn;
        return denom == 0 ? 0.0 : (double) tp / denom;
    }

    public double f1() {
        double p = precision();
        double r = recall();
        return (p + r) == 0.0 ? 0.0 : 2 * p * r / (p + r);
    }
}
