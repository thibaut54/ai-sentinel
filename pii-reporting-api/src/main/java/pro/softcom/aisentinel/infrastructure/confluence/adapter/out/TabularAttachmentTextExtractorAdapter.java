package pro.softcom.aisentinel.infrastructure.confluence.adapter.out;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.domain.confluence.AttachmentInfo;
import pro.softcom.aisentinel.domain.confluence.AttachmentTypeFilter;
import pro.softcom.aisentinel.domain.confluence.extraction.ExtractedContent;
import pro.softcom.aisentinel.infrastructure.confluence.adapter.out.tabular.TabularContentSerializer;

import java.util.Optional;

/**
 * WHAT: Attachment text extractor for natively tabular files (xlsx/xls/csv/ods).
 *
 * <p>Ordered first ({@code @Order(1)}): for tabular attachments it produces an {@link ExtractedContent}
 * whose analysis text pairs each cell value with its column header, while the context text keeps the
 * raw values. When the file has no identifiable header (RG4) or cannot be parsed, it returns
 * {@link Optional#empty()} so the composite falls back to the Tika extractor ({@code @Order(2)}).
 */
@Component
@Order(1)
public class TabularAttachmentTextExtractorAdapter implements AttachmentTextExtractionStrategy {

    private final TabularContentSerializer tabularContentSerializer;

    public TabularAttachmentTextExtractorAdapter(TabularContentSerializer tabularContentSerializer) {
        this.tabularContentSerializer = tabularContentSerializer;
    }

    @Override
    public boolean supports(AttachmentInfo info) {
        return AttachmentTypeFilter.isTabular(info);
    }

    @Override
    public Optional<ExtractedContent> extract(AttachmentInfo info, byte[] bytes) {
        if (info == null || bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        return tabularContentSerializer.serialize(info.extension(), bytes);
    }
}
