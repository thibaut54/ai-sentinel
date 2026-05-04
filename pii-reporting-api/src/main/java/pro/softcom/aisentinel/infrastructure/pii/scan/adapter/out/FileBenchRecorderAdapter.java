package pro.softcom.aisentinel.infrastructure.pii.scan.adapter.out;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiScanBenchRecorderPort;
import pro.softcom.aisentinel.infrastructure.pii.scan.config.BenchProperties;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous file recorder for PII scan bench measurements.
 *
 * <p>Producers (the scan pipeline) call {@link #record} which only does an
 * in-memory {@code offer} on a bounded queue and returns immediately. A single
 * daemon worker thread drains the queue and writes TSV lines to disk, batching
 * flushes to amortise I/O cost. When the queue is saturated, records are
 * dropped (counter logged) — the scan must never block on disk.
 */
@Slf4j
public class FileBenchRecorderAdapter implements PiiScanBenchRecorderPort {

    static final String HEADER = String.join("\t",
        "timestamp", "label", "scanId", "spaceKey", "pageId", "itemKind",
        "charCount", "durationMs", "charsPerSec", "findings");

    private static final String POISON_PILL = "__POISON__";
    private static final long IDLE_POLL_TIMEOUT_MS = 1_000L;
    private static final long SHUTDOWN_JOIN_MS = 5_000L;

    private final BenchProperties props;
    private final BlockingQueue<String> queue;
    private final AtomicLong droppedCount = new AtomicLong();

    private volatile BufferedWriter writer;
    private volatile Thread worker;
    private volatile boolean shuttingDown;

    public FileBenchRecorderAdapter(BenchProperties props) {
        this.props = props;
        int capacity = props.getQueueCapacity() > 0 ? props.getQueueCapacity() : 10_000;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    @PostConstruct
    void start() throws IOException {
        Path path = Paths.get(props.getFile()).toAbsolutePath();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        boolean writeHeader = !Files.exists(path) || Files.size(path) == 0L;
        this.writer = Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
        if (writeHeader) {
            writer.write(HEADER);
            writer.newLine();
            writer.flush();
        }
        this.worker = new Thread(this::runWriterLoop, "ai-sentinel-bench-writer");
        this.worker.setDaemon(true);
        this.worker.start();
        log.info("[BENCH] FileBenchRecorderAdapter started file={} label={} queueCapacity={}",
            path, props.getLabel(), props.getQueueCapacity());
    }

    @Override
    public void record(BenchRecord record) {
        if (shuttingDown || record == null) {
            return;
        }
        String line = formatLine(record);
        if (!queue.offer(line)) {
            long dropped = droppedCount.incrementAndGet();
            if (dropped == 1 || dropped % 100 == 0) {
                log.warn("[BENCH] queue full, dropping records (totalDropped={})", dropped);
            }
        }
    }

    private String formatLine(BenchRecord r) {
        double charsPerSec = r.durationMs() > 0 ? (r.charCount() * 1000.0) / r.durationMs() : 0.0;
        return String.join("\t",
            Instant.now().toString(),
            sanitize(props.getLabel()),
            sanitize(r.scanId()),
            sanitize(r.spaceKey()),
            sanitize(r.itemId()),
            sanitize(r.itemKind()),
            Integer.toString(r.charCount()),
            Long.toString(r.durationMs()),
            String.format(Locale.ROOT, "%.2f", charsPerSec),
            Integer.toString(r.findings()));
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private void runWriterLoop() {
        int sinceFlush = 0;
        try {
            while (true) {
                String line = queue.poll(IDLE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (line == null) {
                    if (sinceFlush > 0) {
                        writer.flush();
                        sinceFlush = 0;
                    }
                    continue;
                }
                if (POISON_PILL.equals(line)) {
                    break;
                }
                writer.write(line);
                writer.newLine();
                sinceFlush++;
                if (sinceFlush >= props.getFlushEveryNLines()) {
                    writer.flush();
                    sinceFlush = 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("[BENCH] writer loop failed: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    void stop() {
        shuttingDown = true;
        try {
            queue.offer(POISON_PILL, 1, TimeUnit.SECONDS);
            if (worker != null) {
                worker.join(SHUTDOWN_JOIN_MS);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                if (writer != null) {
                    writer.flush();
                    writer.close();
                }
            } catch (IOException e) {
                log.warn("[BENCH] error closing writer: {}", e.getMessage());
            }
            log.info("[BENCH] FileBenchRecorderAdapter stopped (totalDropped={})", droppedCount.get());
        }
    }

    long droppedCount() {
        return droppedCount.get();
    }
}
