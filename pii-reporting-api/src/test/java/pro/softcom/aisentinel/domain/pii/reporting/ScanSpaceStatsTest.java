package pro.softcom.aisentinel.domain.pii.reporting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScanSpaceStatsTest {

    private static ScanSpaceStats statsWith(Instant startedAt, Instant finishedAt) {
        return new ScanSpaceStats("scan-1", "KEY", startedAt, finishedAt,
            0, 0, 0L, 0, 0, 0L, List.of(), List.of());
    }

    @Test
    @DisplayName("Should_ComputeWallClockDuration_When_StartedAndFinishedPresent")
    void Should_ComputeWallClockDuration_When_StartedAndFinishedPresent() {
        Instant started = Instant.parse("2026-06-07T10:00:00Z");
        Instant finished = Instant.parse("2026-06-07T10:12:34Z");

        ScanSpaceStats stats = statsWith(started, finished);

        assertThat(stats.durationMs()).isEqualTo(754_000L);
    }

    @Test
    @DisplayName("Should_ReturnNullDuration_When_FinishedAtMissing")
    void Should_ReturnNullDuration_When_FinishedAtMissing() {
        ScanSpaceStats stats = statsWith(Instant.parse("2026-06-07T10:00:00Z"), null);

        assertThat(stats.durationMs()).isNull();
    }

    @Test
    @DisplayName("Should_ReturnNullDuration_When_StartedAtMissing")
    void Should_ReturnNullDuration_When_StartedAtMissing() {
        ScanSpaceStats stats = statsWith(null, Instant.parse("2026-06-07T10:12:34Z"));

        assertThat(stats.durationMs()).isNull();
    }
}
