package pro.softcom.aisentinel.infrastructure.pii.remediation.adapter.out;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalized character stream built from raw text segments, keeping for every emitted
 * character the (segment index, raw offset) it came from so matches found on the
 * normalized text can be re-projected onto the original segments.
 *
 * <p>Normalization: NBSP variants and tabs become plain spaces, zero-width characters
 * are dropped, whitespace runs collapse to a single space, matching stays case-sensitive.
 * Hard separators emit an uncrossable {@code '\n'}; soft separators collapse into a
 * space, mirroring how the production extraction joins table cells.</p>
 */
final class NormalizedText {

    private static final int UNMAPPED = -1;

    private final StringBuilder normalized = new StringBuilder();
    private final List<int[]> origins = new ArrayList<>();
    private boolean pendingSpace;
    private int pendingSegment = UNMAPPED;
    private int pendingOffset = UNMAPPED;

    void append(int segmentIndex, String rawText) {
        for (int i = 0; i < rawText.length(); i++) {
            appendChar(segmentIndex, i, rawText.charAt(i));
        }
    }

    void hardSeparator() {
        pendingSpace = false;
        if (!normalized.isEmpty() && !endsWithNewline()) {
            normalized.append('\n');
            origins.add(new int[]{UNMAPPED, UNMAPPED});
        }
    }

    void softSeparator() {
        if (!endsWithNewline() && !pendingSpace) {
            markPendingSpace(UNMAPPED, UNMAPPED);
        }
    }

    String text() {
        return normalized.toString();
    }

    int segmentAt(int position) {
        return origins.get(position)[0];
    }

    int rawOffsetAt(int position) {
        return origins.get(position)[1];
    }

    static String normalizeValue(String value) {
        NormalizedText stream = new NormalizedText();
        stream.append(0, nfc(value));
        return stream.text();
    }

    static String nfc(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }

    private void appendChar(int segmentIndex, int rawOffset, char c) {
        if (isZeroWidth(c)) {
            return;
        }
        if (isSpaceLike(c)) {
            if (!pendingSpace) {
                markPendingSpace(segmentIndex, rawOffset);
            }
            return;
        }
        flushPendingSpace();
        normalized.append(c);
        origins.add(new int[]{segmentIndex, rawOffset});
    }

    private void markPendingSpace(int segmentIndex, int rawOffset) {
        pendingSpace = true;
        pendingSegment = segmentIndex;
        pendingOffset = rawOffset;
    }

    private void flushPendingSpace() {
        if (!pendingSpace) {
            return;
        }
        if (!normalized.isEmpty() && !endsWithNewline()) {
            normalized.append(' ');
            origins.add(new int[]{pendingSegment, pendingOffset});
        }
        pendingSpace = false;
    }

    private boolean endsWithNewline() {
        return !normalized.isEmpty() && normalized.charAt(normalized.length() - 1) == '\n';
    }

    private static boolean isSpaceLike(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n'
            || c == 0x00A0 || c == 0x202F || c == 0x2007 || c == 0x2009;
    }

    private static boolean isZeroWidth(char c) {
        return c == 0x200B || c == 0x200C || c == 0x200D || c == 0xFEFF || c == 0x00AD;
    }
}
