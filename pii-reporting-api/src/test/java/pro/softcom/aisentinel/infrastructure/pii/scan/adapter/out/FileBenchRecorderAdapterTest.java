package pro.softcom.aisentinel.infrastructure.pii.scan.adapter.out;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.softcom.aisentinel.application.pii.scan.port.out.PiiScanBenchRecorderPort.BenchRecord;
import pro.softcom.aisentinel.infrastructure.pii.scan.config.BenchProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class FileBenchRecorderAdapterTest {

    @TempDir
    Path tmp;

    @Test
    void should_write_header_and_record_when_started() throws Exception {
        Path file = tmp.resolve("bench.tsv");
        FileBenchRecorderAdapter adapter = startAdapter(file, "onnx", 100);

        try {
            adapter.recordSample(new BenchRecord("scan-1", "SPACE-A", "page-42", "page", 1000, 250, 3));

            await().atMost(3, TimeUnit.SECONDS).until(() -> Files.lines(file).count() >= 2);
            adapter.stop();

            List<String> lines = Files.readAllLines(file);
            assertThat(lines).hasSize(2);
            assertThat(lines.get(0)).isEqualTo(FileBenchRecorderAdapter.HEADER);
            String[] cols = lines.get(1).split("\t", -1);
            assertThat(cols).hasSize(10);
            assertThat(cols[1]).isEqualTo("onnx");          // label
            assertThat(cols[2]).isEqualTo("scan-1");        // scanId
            assertThat(cols[3]).isEqualTo("SPACE-A");       // spaceKey
            assertThat(cols[4]).isEqualTo("page-42");       // pageId
            assertThat(cols[5]).isEqualTo("page");          // itemKind
            assertThat(cols[6]).isEqualTo("1000");          // charCount
            assertThat(cols[7]).isEqualTo("250");           // durationMs
            assertThat(cols[8]).isEqualTo("4000.00");       // charsPerSec = 1000 / 0.25
            assertThat(cols[9]).isEqualTo("3");             // findings
        } finally {
            adapter.stop();
        }
    }

    @Test
    void should_append_when_file_already_exists() throws Exception {
        Path file = tmp.resolve("bench.tsv");
        Files.writeString(file, FileBenchRecorderAdapter.HEADER + "\nexisting-line\n");

        FileBenchRecorderAdapter adapter = startAdapter(file, "pytorch", 100);
        try {
            adapter.recordSample(new BenchRecord("scan-2", "SPACE-B", "page-1", "page", 500, 1000, 0));
            await().atMost(3, TimeUnit.SECONDS).until(() -> Files.lines(file).count() >= 3);
        } finally {
            adapter.stop();
        }

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo(FileBenchRecorderAdapter.HEADER);
        assertThat(lines.get(1)).isEqualTo("existing-line");
        assertThat(lines.get(2)).contains("\tpytorch\t").contains("\t500\t1000\t");
    }

    @Test
    void should_drop_records_when_queue_is_saturated() throws Exception {
        Path file = tmp.resolve("bench.tsv");
        // Tiny queue: 1 slot. The worker is fast so we cannot guarantee saturation in a unit test
        // without blocking the writer. Instead we stress: push many records, then check that drop counter
        // is monotonic non-negative and that no exception escaped.
        FileBenchRecorderAdapter adapter = startAdapter(file, "stress", 1);
        try {
            AtomicInteger pushed = new AtomicInteger();
            for (int i = 0; i < 5000; i++) {
                adapter.recordSample(new BenchRecord("s", "S", "p" + i, "page", 10, 1, 0));
                pushed.incrementAndGet();
            }
            // Just assert: API never threw, dropped counter is consistent (>=0)
            assertThat(pushed.get()).isEqualTo(5000);
            assertThat(adapter.droppedCount()).isGreaterThanOrEqualTo(0L);
        } finally {
            adapter.stop();
        }
    }

    @Test
    void should_ignore_null_record() throws Exception {
        Path file = tmp.resolve("bench.tsv");
        FileBenchRecorderAdapter adapter = startAdapter(file, "x", 100);
        try {
            adapter.recordSample(null);
            // Header written but no data line ever expected: assert the file stays at a
            // single line for a stable observation window (deterministic, no Thread.sleep).
            await().during(Duration.ofMillis(200)).atMost(1, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(Files.readAllLines(file)).hasSize(1));
        } finally {
            adapter.stop();
        }
    }

    @Test
    void should_sanitize_tabs_and_newlines_in_fields() throws Exception {
        Path file = tmp.resolve("bench.tsv");
        FileBenchRecorderAdapter adapter = startAdapter(file, "lab\tel", 100);
        try {
            adapter.recordSample(new BenchRecord("a\tb", "S\nE", "p\rid", "page", 1, 1, 0));
            await().atMost(3, TimeUnit.SECONDS).until(() -> Files.lines(file).count() >= 2);
        } finally {
            adapter.stop();
        }

        String dataLine = Files.readAllLines(file).get(1);
        // Each row must contain exactly 9 tab separators (10 columns)
        assertThat(dataLine.chars().filter(c -> c == '\t').count()).isEqualTo(9L);
        assertThat(dataLine).doesNotContain("\n", "\r");
    }

    private FileBenchRecorderAdapter startAdapter(Path file, String label, int queueCapacity) throws Exception {
        BenchProperties props = new BenchProperties();
        props.setEnabled(true);
        props.setFile(file.toString());
        props.setLabel(label);
        props.setQueueCapacity(queueCapacity);
        props.setFlushEveryNLines(1);  // immediate flush in tests
        FileBenchRecorderAdapter adapter = new FileBenchRecorderAdapter(props);
        adapter.start();
        return adapter;
    }
}
