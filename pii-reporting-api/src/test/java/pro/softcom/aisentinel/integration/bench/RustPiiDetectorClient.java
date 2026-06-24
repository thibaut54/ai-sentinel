package pro.softcom.aisentinel.integration.bench;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import pii_detection.PIIDetectionServiceGrpc;
import pii_detection.PiiDetection;
import pro.softcom.aisentinel.integration.bench.LlmExtractorClient.RawEntity;

/**
 * Minimal gRPC client for the standalone Rust detector service
 * ({@code pii-detector-rust}), used only by the 3-way benchmark.
 *
 * <p>The Rust service implements the same {@code pii_detection.PIIDetectionService}
 * contract as the Python service, so the generated stub is reused verbatim — this
 * exercises the <em>whole</em> Rust pipeline (regex + GLiNER2 NER + classification)
 * end-to-end over gRPC, exactly as production would call it. A dedicated channel is
 * needed because the Spring-wired {@code PiiDetectorClient} points at the Python
 * container; the two detector containers must be reached independently.
 *
 * <p>The Rust proto omits the {@code source} field, so over the wire every entity's
 * {@code type} is the taxonomy IPI id (e.g. {@code AVS_NUMBER}) and there is no
 * detector source — detections are therefore projected to canonical concepts by
 * their type alone, via {@link ExtractorConceptMap} (the {@code gliner-rust} map),
 * symmetric to how the LLM extractor's labels are projected.
 */
final class RustPiiDetectorClient implements AutoCloseable {

    private final ManagedChannel channel;
    private final PIIDetectionServiceGrpc.PIIDetectionServiceBlockingStub stub;

    RustPiiDetectorClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .maxInboundMessageSize(16 * 1024 * 1024)
            .build();
        this.stub = PIIDetectionServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Runs the full Rust pipeline on {@code text} and returns its findings as
     * {@code (value, label=IPI id)} pairs — the same shape the LLM extractor
     * produces, so both can be projected and scored identically.
     */
    List<RawEntity> detect(String text, long timeoutMs) {
        PiiDetection.PIIDetectionRequest request = PiiDetection.PIIDetectionRequest.newBuilder()
            .setContent(text == null ? "" : text)
            .build();
        PiiDetection.PIIDetectionResponse response = stub
            .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
            .detectPII(request);
        return response.getEntitiesList().stream()
            .map(e -> new RawEntity(e.getText(), e.getType()))
            .toList();
    }

    @Override
    public void close() {
        channel.shutdownNow();
    }
}
