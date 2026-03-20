package pro.softcom.aisentinel.application.pii.scan.port.out;

import pro.softcom.aisentinel.domain.pii.scan.model.ScanSourceConfig;
import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import reactor.core.publisher.Flux;

/**
 * Port for loading content from any source (Confluence, Database, etc.).
 */
public interface LoadContentPort {
    /**
     * Loads all scannable content from the specified source.
     *
     * @param config configuration for the source
     * @return Flux of content items
     */
    Flux<ScannableContent> loadContent(ScanSourceConfig config);
}
