package pro.softcom.aisentinel.application.pii.detection.port.in;

import pro.softcom.aisentinel.domain.pii.detection.ConcurrencyBenchStatus;

/**
 * Port IN for the on-demand Ministral concurrency benchmark.
 * Defines use cases for requesting a benchmark run and polling its status.
 */
public interface ManageConcurrencyBenchmarkPort {

    /**
     * Requests an on-demand concurrency benchmark run.
     * The detector service picks up the request and executes the benchmark.
     */
    void requestBenchmark();

    /**
     * Retrieves the current benchmark job status together with the currently
     * applied concurrency values.
     *
     * @return The current benchmark status snapshot
     */
    ConcurrencyBenchStatus getBenchStatus();
}
