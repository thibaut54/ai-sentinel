-- ============================================================================
-- On-demand concurrency benchmark job state — additive, idempotent migration.
-- ============================================================================
-- Lets an operator trigger the Ministral concurrency benchmark from the UI
-- without restarting the service, and lets the UI render a progress bar.
--
-- Flow: the UI (via the API) sets concurrency_bench_requested = true. A poller
-- thread in the Python detector service picks it up, sets status = RUNNING and
-- updates concurrency_bench_progress / concurrency_bench_message as each
-- concurrency level is measured, then writes the winning ministral_concurrency
-- (+ ministral_concurrency_tuned_signature) and status = DONE (or FAILED).
--
-- status values: IDLE (never run) | PENDING (requested) | RUNNING | DONE | FAILED
-- progress: 0..100 (percent)
-- ============================================================================

ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS concurrency_bench_requested BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS concurrency_bench_status VARCHAR(20) NOT NULL DEFAULT 'IDLE';
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS concurrency_bench_progress INTEGER NOT NULL DEFAULT 0;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS concurrency_bench_message VARCHAR(255);

COMMENT ON COLUMN pii_detection_config.concurrency_bench_requested IS
    'Set to true by the UI/API to request an on-demand concurrency benchmark; cleared by the detector service when it picks the job up.';
COMMENT ON COLUMN pii_detection_config.concurrency_bench_status IS
    'On-demand benchmark job status: IDLE | PENDING | RUNNING | DONE | FAILED.';
COMMENT ON COLUMN pii_detection_config.concurrency_bench_progress IS
    'On-demand benchmark progress percentage (0..100).';
COMMENT ON COLUMN pii_detection_config.concurrency_bench_message IS
    'Human-readable progress label for the on-demand benchmark (e.g. "Testing concurrency 2/4").';

ALTER TABLE pii_detection_config DROP CONSTRAINT IF EXISTS chk_concurrency_bench_progress_range;
ALTER TABLE pii_detection_config
    ADD CONSTRAINT chk_concurrency_bench_progress_range
    CHECK (concurrency_bench_progress BETWEEN 0 AND 100);
