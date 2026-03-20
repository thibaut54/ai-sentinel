package pro.softcom.aisentinel.infrastructure.sharepoint.adapter.out;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import pro.softcom.aisentinel.application.sharepoint.service.SharePointTextExtractorPort;

import java.io.InputStream;

/**
 * Extracts text from SharePoint file streams using Apache Tika.
 * Supports PDF, DOCX, XLSX, PPTX, TXT and other common formats.
 */
@Component
@Slf4j
public class TikaSharePointTextExtractor implements SharePointTextExtractorPort {

    @Override
    public String extractText(InputStream inputStream, String fileName, String mimeType) {
        if (inputStream == null) {
            return "";
        }

        try (inputStream) {
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();

            parser.parse(inputStream, handler, metadata, context);
            String text = StringUtils.trim(handler.toString());

            if (text == null || text.isEmpty()) {
                log.debug("[SHAREPOINT-TIKA] No text extracted from file: {}", fileName);
                return "";
            }

            log.debug("[SHAREPOINT-TIKA] Extracted {} chars from file: {}", text.length(), fileName);
            return text;

        } catch (Exception e) {
            log.warn("[SHAREPOINT-TIKA] Error extracting text from file '{}': {}", fileName, e.getMessage());
            return "";
        }
    }
}
