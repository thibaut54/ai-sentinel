-- ============================================================================
-- Ministral-PII detector integration — additive, idempotent migration.
-- ============================================================================
-- The schema is primarily managed by Hibernate ddl-auto (these columns are
-- declared on PiiDetectionConfigEntity). This script keeps Docker-bootstrapped
-- environments consistent and is safe to run multiple times.
--
-- Ministral-PII is a specialised LLM detector (+24 F1 vs GLiNER2). Unlike the
-- other detectors it is permanently exempt from the LLM-as-judge (same model
-- nature), so ministral_judge_enabled is a structural column kept FALSE and
-- never exposed in the UI or domain. ministral_chunk_size / ministral_overlap
-- drive the detector's configurable text chunking.
-- ============================================================================

-- 1. Ministral columns on the singleton config.
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS ministral_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS ministral_chunk_size INTEGER NOT NULL DEFAULT 1024;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS ministral_overlap INTEGER NOT NULL DEFAULT 128;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS ministral_judge_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN pii_detection_config.ministral_enabled IS
    'Enables the Ministral-PII detector (specialised LLM, +24 F1 vs GLiNER2). Default: false (explicit operator opt-in).';
COMMENT ON COLUMN pii_detection_config.ministral_chunk_size IS
    'Ministral-PII chunk size in tokens (range 256-4096). Default: 1024.';
COMMENT ON COLUMN pii_detection_config.ministral_overlap IS
    'Ministral-PII chunk overlap in tokens (range 0-512, strictly < ministral_chunk_size). Default: 128.';
COMMENT ON COLUMN pii_detection_config.ministral_judge_enabled IS
    'Structural column kept FALSE: Ministral-PII is permanently exempt from the LLM-as-judge (same model nature). Never exposed in the UI/domain.';

-- 2. Coherence CHECK: chunk overlap must stay strictly below chunk size.
ALTER TABLE pii_detection_config DROP CONSTRAINT IF EXISTS chk_ministral_overlap_lt_chunk_size;
ALTER TABLE pii_detection_config
    ADD CONSTRAINT chk_ministral_overlap_lt_chunk_size
    CHECK (ministral_overlap < ministral_chunk_size);

-- 3. Widen the detector CHECK constraint (init-scripts 006/009) so it accepts
--    MINISTRAL. ddl-auto is the source of truth for the JPA schema and does not
--    enforce this CHECK, but a Docker-bootstrapped environment that ran 006/009
--    would otherwise reject MINISTRAL inserts. DROP + recreate is idempotent.
ALTER TABLE pii_type_config DROP CONSTRAINT IF EXISTS check_detector;
ALTER TABLE pii_type_config
    ADD CONSTRAINT check_detector
    CHECK (detector IN ('GLINER', 'PRESIDIO', 'REGEX', 'OPENMED', 'GLINER2', 'MINISTRAL'));
