package pro.softcom.aisentinel.application.pii.scan.port.out;

import pro.softcom.aisentinel.domain.pii.scan.model.ScannableContent;
import reactor.core.publisher.Flux;

/**
 * Port for loading content from any source (Confluence, Database, etc.).
 */
public interface LoadContentPort {
    /**
     * Loads all scannable content from the specified source.
     *
     * @param sourceIdentifier identifier for the source (e.g., spaceKey for Confluence, table name for DB)
     * @return Flux of content items
     */
    Flux<ScannableContent> loadContent(String sourceIdentifier);
}
