package pro.softcom.aisentinel.application.pii.reporting.port.out;

import pro.softcom.aisentinel.domain.pii.export.SourceType;
import pro.softcom.aisentinel.domain.pii.reporting.ContentScanResult;

/**
 * Out-port for appending scan events to the event store.
 * Infrastructure adapters (e.g., JPA) must implement this port.
 */
public interface ScanEventStore {
    /**
     * Append one event to the event store with its source type discriminator.
     *
     * @param event      the scan event to persist
     * @param sourceType the type of the datasource that produced this event
     */
    void append(ContentScanResult event, SourceType sourceType);

    void deleteAll();

    void deleteBySourceType(SourceType sourceType);

    void deleteBySourceTypeAndSourceKeys(SourceType sourceType, java.util.List<String> sourceKeys);
}
