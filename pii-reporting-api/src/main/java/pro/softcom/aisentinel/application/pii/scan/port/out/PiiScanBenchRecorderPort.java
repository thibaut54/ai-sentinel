package pro.softcom.aisentinel.application.pii.scan.port.out;

/**
 * Outbound port for recording PII scan throughput metrics for offline benchmarking.
 *
 * <p>Implementations must be non-blocking from the caller's perspective: the scan
 * pipeline must never be slowed down by the recorder. Producers offer records and
 * a background worker handles persistence.
 *
 * <p>Activated only when {@code ai-sentinel.scan.bench.enabled=true}; otherwise a
 * no-op implementation is wired so the use case stays oblivious to the toggle.
 */
public interface PiiScanBenchRecorderPort {

    /**
     * Submits a single scan measurement for asynchronous persistence.
     *
     * <p>This call must return in microseconds and must not throw checked
     * exceptions. If the underlying queue is saturated the record may be
     * dropped silently (with a counter logged elsewhere) — the contract
     * favours scan throughput over measurement completeness.
     *
     * @param record the immutable measurement to persist
     */
    void record(BenchRecord record);

    /**
     * Single bench measurement captured around a gRPC call to the PII detector.
     *
     * @param scanId     identifier of the running scan
     * @param spaceKey   business key of the Confluence space currently scanned
     * @param itemId     stable identifier of the analysed item (page id, or
     *                   {@code pageId::attachmentName} for attachments)
     * @param itemKind   either {@code "page"} or {@code "attachment"}
     * @param charCount  number of characters submitted to the detector
     * @param durationMs wall-clock duration of the gRPC call, in milliseconds
     * @param findings   number of sensitive data items returned by the detector
     */
    record BenchRecord(
        String scanId,
        String spaceKey,
        String itemId,
        String itemKind,
        int charCount,
        long durationMs,
        int findings
    ) {
    }
}
