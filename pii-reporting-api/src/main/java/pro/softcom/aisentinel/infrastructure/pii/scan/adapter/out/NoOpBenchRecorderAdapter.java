package pro.softcom.aisentinel.infrastructure.pii.scan.adapter.out;

import pro.softcom.aisentinel.application.pii.scan.port.out.PiiScanBenchRecorderPort;

/**
 * Default no-op recorder wired when {@code ai-sentinel.scan.bench.enabled=false}.
 *
 * <p>Kept dead-simple so the use case can call the recorder unconditionally
 * without paying any branch cost in the hot scan path.
 */
public class NoOpBenchRecorderAdapter implements PiiScanBenchRecorderPort {
    @Override
    public void recordSample(BenchRecord sample) {
        // intentionally empty — bench disabled
    }
}
