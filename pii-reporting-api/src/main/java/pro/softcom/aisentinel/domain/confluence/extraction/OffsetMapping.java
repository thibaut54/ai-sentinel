package pro.softcom.aisentinel.domain.confluence.extraction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Ordered correspondence between offsets in the "analysis" text space and offsets
 * in the "context" text space produced from the same source reading.
 *
 * <p>Business purpose: tabular extraction emits two texts from one reading — an analysis
 * text that prefixes every cell value with its column header ({@code Header : value | ...})
 * and a context text that keeps the raw values only. A detector runs on the analysis text and
 * returns positions in that space; those positions must be remapped to the context space so the
 * report shows raw values with coherent masking offsets.
 *
 * <p>A {@link Segment} anchors one cell value: the same characters appear verbatim in both spaces,
 * so a detection that falls entirely inside a value's analysis range maps linearly to the context
 * range. A detection that falls on a synthetic label ({@code Header : }) or a separator ({@code |})
 * — i.e. outside any value segment, or straddling two segments — cannot be remapped and is dropped.
 *
 * <p>The identity mapping (empty segment list) models the non-tabular case where the analysis text
 * and the context text are the same string; {@link #remap(int, int)} then returns the range unchanged.
 */
public record OffsetMapping(List<Segment> segments) {

    /**
     * One verbatim value occurrence shared by both text spaces.
     *
     * @param analysisStart start offset of the value in the analysis text
     * @param length number of characters of the value (identical in both spaces)
     * @param contextStart start offset of the same value in the context text
     */
    public record Segment(int analysisStart, int length, int contextStart) {
        public Segment {
            if (analysisStart < 0 || contextStart < 0 || length <= 0) {
                throw new IllegalArgumentException(
                    "Segment offsets must be non-negative and length strictly positive");
            }
        }
    }

    /**
     * A remapped half-open range {@code [start, end)} in the context text space.
     */
    public record Span(int start, int end) {
    }

    /**
     * Canonical constructor: defensively copies the segments and keeps them sorted by
     * {@code analysisStart} so {@link #remap(int, int)} can binary-search a non-overlapping space.
     */
    public OffsetMapping(List<Segment> segments) {
        List<Segment> copy = segments == null ? new ArrayList<>() : new ArrayList<>(segments);
        copy.sort(Comparator.comparingInt(Segment::analysisStart));
        this.segments = List.copyOf(copy);
    }

    /**
     * Identity mapping for non-tabular content where the analysis and context texts are identical.
     */
    public static OffsetMapping identity() {
        return new OffsetMapping(List.of());
    }

    /**
     * Whether this mapping is the identity (no transformation between the two text spaces).
     */
    public boolean isIdentity() {
        return segments.isEmpty();
    }

    /**
     * Remaps an analysis-space half-open range {@code [start, end)} to the context space.
     *
     * @return the context-space range when {@code [start, end)} lies entirely within a single value
     *         segment (or always, for the identity mapping); {@link Optional#empty()} when the range
     *         is invalid, zero-length on a tabular mapping, falls outside every value segment, or
     *         straddles a segment boundary.
     */
    public Optional<Span> remap(int start, int end) {
        if (start < 0 || end < start) {
            return Optional.empty();
        }
        if (isIdentity()) {
            return Optional.of(new Span(start, end));
        }
        if (start == end) {
            return Optional.empty();
        }
        return findContainingSegment(start)
            .filter(segment -> end <= segment.analysisStart() + segment.length())
            .map(segment -> {
                int delta = start - segment.analysisStart();
                int contextStart = segment.contextStart() + delta;
                return new Span(contextStart, contextStart + (end - start));
            });
    }

    private Optional<Segment> findContainingSegment(int analysisOffset) {
        int low = 0;
        int high = segments.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Segment segment = segments.get(mid);
            int segmentStart = segment.analysisStart();
            int segmentEnd = segmentStart + segment.length();
            if (analysisOffset < segmentStart) {
                high = mid - 1;
            } else if (analysisOffset >= segmentEnd) {
                low = mid + 1;
            } else {
                return Optional.of(segment);
            }
        }
        return Optional.empty();
    }
}
