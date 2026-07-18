package pro.softcom.aisentinel.application.pii.detection.usecase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pro.softcom.aisentinel.application.pii.detection.port.in.ManageConcurrencyBenchmarkPort;
import pro.softcom.aisentinel.application.pii.detection.port.out.PiiDetectionConfigRepository;
import pro.softcom.aisentinel.domain.pii.detection.ConcurrencyBenchStatus;

/**
 * Use case for the on-demand Ministral concurrency benchmark.
 * Handles benchmark run requests and status polling; the benchmark itself is
 * executed by the detector service, which reports progress through the
 * configuration row.
 */
public class ManageConcurrencyBenchmarkUseCase implements ManageConcurrencyBenchmarkPort {

    private static final Logger log = LoggerFactory.getLogger(ManageConcurrencyBenchmarkUseCase.class);

    private final PiiDetectionConfigRepository repository;

    public ManageConcurrencyBenchmarkUseCase(PiiDetectionConfigRepository repository) {
        this.repository = repository;
    }

    @Override
    public void requestBenchmark() {
        ConcurrencyBenchStatus current = repository.findBenchStatus();
        if (isInProgress(current)) {
            log.info(
                "Concurrency benchmark already {} — ignoring duplicate request",
                current.status()
            );
            return;
        }
        log.info("Requesting on-demand Ministral concurrency benchmark run");
        repository.requestBenchmark();
    }

    /**
     * A benchmark is in progress once it has been requested (PENDING) or is
     * actively RUNNING. Re-arming the request flag while in progress would make
     * the detector service run a redundant second benchmark right after the
     * current one, so duplicate requests are ignored.
     */
    private static boolean isInProgress(ConcurrencyBenchStatus status) {
        if (status == null) {
            return false;
        }
        return "PENDING".equals(status.status()) || "RUNNING".equals(status.status());
    }

    @Override
    public ConcurrencyBenchStatus getBenchStatus() {
        log.debug("Retrieving concurrency benchmark status");
        return repository.findBenchStatus();
    }
}
