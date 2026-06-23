package pro.softcom.aisentinel.application.confluence.port.out;

import pro.softcom.aisentinel.domain.confluence.AttachmentInfo;
import pro.softcom.aisentinel.domain.confluence.extraction.ExtractedContent;

import java.util.Optional;

/**
 * Outbound port: text extraction from attachments.
 *
 * <p>Returns an {@link ExtractedContent} carrying both the analysis text (sent to the detector) and
 * the context text (rendered in the report), plus their offset correspondence. Non-tabular
 * extractors return an identity content where the two texts are equal.
 */
public interface AttachmentTextExtractor {
    Optional<ExtractedContent> extractText(AttachmentInfo info, byte[] bytes);
}
