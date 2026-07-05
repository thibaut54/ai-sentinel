package pro.softcom.aisentinel.infrastructure.pii.scan.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the asynchronous file-based bench recorder.
 *
 * <p>Disabled by default. Activate via env or yaml only when running A/B
 * benchmark scans (e.g. ONNX vs PyTorch backend).
 */
@ConfigurationProperties(prefix = "ai-sentinel.scan.bench")
@Getter
@Setter
public class BenchProperties {

    /** Toggle the file recorder. When false the no-op recorder is wired. */
    private boolean enabled = false;

    /** Output TSV file path (relative to the working directory). */
    private String file = "bench-pii-scan.tsv";

    /** Free-form label written on every row (e.g. "onnx", "pytorch", "with-threads"). */
    private String label = "default";

    /** Producer queue capacity. Records are dropped when saturated. */
    private int queueCapacity = 10_000;

    /** Worker flushes the file every N writes (or every poll timeout when idle). */
    private int flushEveryNLines = 50;
}
