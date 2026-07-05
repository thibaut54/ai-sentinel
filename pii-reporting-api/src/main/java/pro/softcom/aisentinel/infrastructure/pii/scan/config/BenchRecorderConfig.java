package pro.softcom.aisentinel.infrastructure.pii.scan.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiScanBenchRecorderPort;
import pro.softcom.aisentinel.infrastructure.pii.scan.adapter.out.FileBenchRecorderAdapter;
import pro.softcom.aisentinel.infrastructure.pii.scan.adapter.out.NoOpBenchRecorderAdapter;

/**
 * Wires the bench recorder as either the asynchronous file adapter or a no-op
 * stub, based on {@code ai-sentinel.scan.bench.enabled}. The use case always
 * receives a single {@link PiiScanBenchRecorderPort} bean.
 */
@Configuration
@EnableConfigurationProperties(BenchProperties.class)
public class BenchRecorderConfig {

    @Bean
    @ConditionalOnProperty(prefix = "ai-sentinel.scan.bench", name = "enabled", havingValue = "true")
    public PiiScanBenchRecorderPort fileBenchRecorder(BenchProperties props) {
        return new FileBenchRecorderAdapter(props);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai-sentinel.scan.bench", name = "enabled", havingValue = "false", matchIfMissing = true)
    public PiiScanBenchRecorderPort noOpBenchRecorder() {
        return new NoOpBenchRecorderAdapter();
    }
}
