package pro.softcom.aisentinel.integration.bench;

/**
 * A single labelled span in the gold (ground-truth) dataset.
 *
 * <p>Offsets are <strong>character code-point</strong> indices into the document
 * text, matching the convention used by the Python detector service (and thus by
 * {@code SensitiveData.position()/end()} for BMP-only text). The benchmark drops
 * documents containing supplementary (non-BMP) characters at conversion time so
 * that this convention holds end to end.
 *
 * @param start inclusive start offset (code points)
 * @param end   exclusive end offset (code points)
 * @param label for gold spans: the canonical concept (e.g. {@code IBAN}); for
 *              ignore spans: the original dataset label (used only for reporting)
 */
public record GoldSpan(int start, int end, String label) {

    /** True when this span and {@code other} share at least one character. */
    public boolean overlaps(int otherStart, int otherEnd) {
        return start < otherEnd && otherStart < end;
    }

    /** True when this span has exactly the same boundaries as {@code (s, e)}. */
    public boolean sameBoundaries(int s, int e) {
        return start == s && end == e;
    }
}
