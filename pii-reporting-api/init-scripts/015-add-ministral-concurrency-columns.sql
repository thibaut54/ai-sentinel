-- ============================================================================
-- Ministral concurrency auto-tuning — additive, idempotent migration.
-- ============================================================================
-- The schema is primarily managed by Hibernate ddl-auto (these columns are
-- declared on PiiDetectionConfigEntity). This script keeps Docker-bootstrapped
-- environments consistent and is safe to run multiple times.
--
-- ministral_concurrency: number of chunk prompts the Ministral detector sends to
--   the LM Studio endpoint concurrently (1 = sequential, the historical
--   behaviour). Read by the Python detector at scan start (fetch_config_from_db).
-- ministral_concurrency_auto: when true, the service auto-tunes the value at
--   startup by micro-benchmarking the LM Studio endpoint; when false, the value
--   is operator-pinned and never overwritten.
-- ministral_concurrency_tuned_signature: the "host:port|model" the current auto
--   value was tuned for. NULL means "never tuned / re-tune at next startup".
-- ============================================================================

ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS ministral_concurrency INTEGER NOT NULL DEFAULT 1;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS ministral_concurrency_auto BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS ministral_concurrency_tuned_signature VARCHAR(255);

COMMENT ON COLUMN pii_detection_config.ministral_concurrency IS
    'Number of chunk prompts sent concurrently to LM Studio by the Ministral detector (1 = sequential). Default: 1.';
COMMENT ON COLUMN pii_detection_config.ministral_concurrency_auto IS
    'When true, the service auto-tunes ministral_concurrency at startup; when false, the value is operator-pinned. Default: true.';
COMMENT ON COLUMN pii_detection_config.ministral_concurrency_tuned_signature IS
    'The "host:port|model" the current auto-tuned concurrency was measured for. NULL = re-tune at next startup.';

ALTER TABLE pii_detection_config DROP CONSTRAINT IF EXISTS chk_ministral_concurrency_range;
ALTER TABLE pii_detection_config
    ADD CONSTRAINT chk_ministral_concurrency_range
    CHECK (ministral_concurrency BETWEEN 1 AND 16);
