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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronous file recorder for PII scan bench measurements.
 *
 * <p>Producers (the scan pipeline) call {@link #recordSample} which only does an
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

    private final AtomicReference<BufferedWriter> writerRef = new AtomicReference<>();
    private final AtomicReference<Thread> workerRef = new AtomicReference<>();
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
        BufferedWriter newWriter = Files.newBufferedWriter(
            path,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
        if (writeHeader) {
            newWriter.write(HEADER);
            newWriter.newLine();
            newWriter.flush();
        }
        this.writerRef.set(newWriter);
        Thread newWorker = new Thread(this::runWriterLoop, "ai-sentinel-bench-writer");
        newWorker.setDaemon(true);
        this.workerRef.set(newWorker);
        newWorker.start();
        log.info("[BENCH] FileBenchRecorderAdapter started file={} label={} queueCapacity={}",
            path, props.getLabel(), props.getQueueCapacity());
    }

    @Override
    public void recordSample(BenchRecord sample) {
        if (shuttingDown || sample == null) {
            return;
        }
        String line = formatLine(sample);
        if (!queue.offer(line)) {
            long dropped = droppedCount.incrementAndGet();
            if (dropped == 1 || dropped % 100 == 0) {
                log.warn("[BENCH] queue full, dropping records (totalDropped={})", dropped);
            }
        }
    }

    private String formatLine(BenchRecord benchRecord) {
        double charsPerSec = benchRecord.durationMs() > 0 ? (benchRecord.charCount() * 1000.0) / benchRecord.durationMs() : 0.0;
        return String.join("\t",
            Instant.now().toString(),
            sanitize(props.getLabel()),
            sanitize(benchRecord.scanId()),
            sanitize(benchRecord.spaceKey()),
            sanitize(benchRecord.itemId()),
            sanitize(benchRecord.itemKind()),
            Integer.toString(benchRecord.charCount()),
            Long.toString(benchRecord.durationMs()),
            String.format(Locale.ROOT, "%.2f", charsPerSec),
            Integer.toString(benchRecord.findings()));
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private void runWriterLoop() {
        BufferedWriter writer = writerRef.get();
        int sinceFlush = 0;
        try {
            boolean draining = true;
            while (draining) {
                String line = queue.poll(IDLE_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (POISON_PILL.equals(line)) {
                    draining = false;
                } else {
                    sinceFlush = handlePolledLine(writer, line, sinceFlush);
                }
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.error("[BENCH] writer loop failed: {}", e.getMessage(), e);
        }
    }

    private int handlePolledLine(BufferedWriter writer, String line, int sinceFlush) throws IOException {
        if (line == null) {
            return flushIfPending(writer, sinceFlush);
        }
        writer.write(line);
        writer.newLine();
        int pending = sinceFlush + 1;
        if (pending >= props.getFlushEveryNLines()) {
            writer.flush();
            return 0;
        }
        return pending;
    }

    private int flushIfPending(BufferedWriter writer, int sinceFlush) throws IOException {
        if (sinceFlush > 0) {
            writer.flush();
            return 0;
        }
        return sinceFlush;
    }

    @PreDestroy
    void stop() {
        shuttingDown = true;
        try {
            boolean poisonAccepted = queue.offer(POISON_PILL, 1, TimeUnit.SECONDS);
            if (!poisonAccepted) {
                log.warn("[BENCH] could not enqueue poison pill within timeout; interrupting writer");
            }
            Thread worker = workerRef.get();
            if (worker != null) {
                if (!poisonAccepted) {
                    worker.interrupt();
                }
                worker.join(SHUTDOWN_JOIN_MS);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                BufferedWriter writer = writerRef.get();
                if (writer != null) {
                    writer.flush();
                    writer.close();
                }
            } catch (IOException _) {
                log.warn("[BENCH] error closing writer");
            }
            log.info("[BENCH] FileBenchRecorderAdapter stopped (totalDropped={})", droppedCount.get());
        }
    }

    long droppedCount() {
        return droppedCount.get();
    }
}
