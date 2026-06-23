package pro.softcom.aisentinel.application.pii.reporting.service;

import pro.softcom.aisentinel.domain.confluence.AttachmentInfo;
import pro.softcom.aisentinel.domain.confluence.extraction.ExtractedContent;

/**
 * Result of text extraction from an attachment.
 *
 * <p>Carries the {@link ExtractedContent} (analysis text + context text + offset mapping) so the
 * scan use case can detect on the analysis text and report on the context text.
 */
public record AttachmentTextExtracted(AttachmentInfo attachment, ExtractedContent content) {
}
