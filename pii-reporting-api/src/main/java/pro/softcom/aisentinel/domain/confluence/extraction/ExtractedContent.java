package pro.softcom.aisentinel.domain.confluence.extraction;

import java.util.Objects;

/**
 * Result of extracting text from an attachment, carrying the two texts produced from one reading
 * plus their offset correspondence.
 *
 * <p>Business purpose: keeps the report unchanged while improving tabular detection. The
 * {@code analysisText} prefixes each cell value with its column header (sent to the detector); the
 * {@code contextText} renders the raw values without any decoration (becomes the report's
 * {@code sourceContent}); the {@code offsetMapping} remaps detector positions from the analysis
 * space back to the context space.
 *
 * <p>For non-tabular content the two texts are identical and the mapping is the identity (see
 * {@link #identity(String)}), so the existing Tika-based flow keeps producing the exact same report.
 */
public record ExtractedContent(String analysisText, String contextText, OffsetMapping offsetMapping) {

    public ExtractedContent {
        Objects.requireNonNull(analysisText, "analysisText");
        Objects.requireNonNull(contextText, "contextText");
        offsetMapping = offsetMapping == null ? OffsetMapping.identity() : offsetMapping;
    }

    /**
     * Builds an identity content where the analysis and context texts are the same string and no
     * offset transformation is applied. Used by the non-tabular extraction path.
     */
    public static ExtractedContent identity(String text) {
        Objects.requireNonNull(text, "text");
        return new ExtractedContent(text, text, OffsetMapping.identity());
    }

    /**
     * Whether this content needs no offset remapping (non-tabular / identity case).
     */
    public boolean isIdentity() {
        return offsetMapping.isIdentity();
    }
}
