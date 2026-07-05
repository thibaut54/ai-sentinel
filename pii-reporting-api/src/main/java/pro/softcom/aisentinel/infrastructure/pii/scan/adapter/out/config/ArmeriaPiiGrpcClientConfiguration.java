package pro.softcom.aisentinel.infrastructure.pii.scan.adapter.out.config;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.grpc.GrpcClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pii_detection.PIIDetectionServiceGrpc;

import java.time.Duration;

/**
 * Provides an Armeria-based gRPC client stub for the PII Detection microservice.
 * This configuration is activated when property 'pii-detector.client=armeria'.
 * Business intent: offer a more resilient HTTP/2/gRPC client with keepalive and
 * deadline support, while keeping the domain API unchanged.
 */
@Configuration
@ConditionalOnProperty(name = "pii-detector.client", havingValue = "armeria")
public class ArmeriaPiiGrpcClientConfiguration {

    /**
     * Builds an Armeria gRPC blocking stub targeting the configured PII service.
     * Uses HTTP/2 cleartext (gproto+h2c) and normalizes localhost to 127.0.0.1 for Windows stability.
     * Applies response/connect timeouts from configuration to avoid default 15s deadline.
     *
     * <p>Long-running RPCs (e.g. PII detection on 100k-char Excel sheets that take
     * 200+ seconds of Ministral + Presidio + Regex) used to fail with
     * {@code com.linecorp.armeria.common.ClosedSessionException} wrapped as a
     * {@code grpc UNKNOWN}. Cause: Armeria's default {@code idleTimeoutMillis}
     * (10s) and {@code pingIntervalMillis} (0 = disabled) close the HTTP/2
     * connection when no bytes flow over the wire — and during a blocking RPC
     * waiting for the server response, no bytes flow.
     *
     * <p>Fix: bump the connection-level idle timeout to {@code requestTimeoutMs}
     * (same as the RPC deadline) and enable an HTTP/2 keepalive ping every 30s so
     * the connection stays alive across long inferences. Without these settings,
     * any single request &gt; 10s on this client risks being cut mid-flight.
     */
    @Bean
    public PIIDetectionServiceGrpc.PIIDetectionServiceBlockingStub piiDetectionBlockingStub(PiiDetectorConfig config) {
        String host = normalizeHost(config.host());
        String uri = String.format("gproto+h2c://%s:%d", host, config.port());
        long requestTimeoutMs = config.requestTimeoutMs();

        // Important: do NOT enable pingInterval. Frequent HTTP/2 PINGs against the
        // Python gRPC server trigger {@code GOAWAY ENHANCE_YOUR_CALM} (default
        // {@code grpc.http2.max_pings_without_data=2}). idleTimeout alone is enough
        // here — we only have a single long blocking RPC, no need for active liveness
        // probes from the client side.
        ClientFactory factory = ClientFactory.builder()
                .idleTimeout(Duration.ofMillis(requestTimeoutMs))
                .connectTimeout(Duration.ofMillis(Math.min(30_000L, config.connectionTimeoutMs())))
                .build();

        return GrpcClients.builder(uri)
                .factory(factory)
                .responseTimeoutMillis(requestTimeoutMs)
                .writeTimeoutMillis(requestTimeoutMs)
                .build(PIIDetectionServiceGrpc.PIIDetectionServiceBlockingStub.class);
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) return "127.0.0.1";
        String h = host.trim();
        return ("localhost".equalsIgnoreCase(h)) ? "127.0.0.1" : h;
    }
}
