-- ============================================================================
-- GLiNER2 detector integration — additive, idempotent migration.
-- ============================================================================
-- The schema is primarily managed by Hibernate ddl-auto (these columns are
-- declared on the JPA entities). This script keeps Docker-bootstrapped
-- environments consistent and is safe to run multiple times.
--
-- 1. pii_detection_config.gliner2_enabled  : global GLiNER2 kill-switch
--    (BOOLEAN NOT NULL DEFAULT FALSE — disabled by default, spec D4).
-- 2. pii_type_config.detector_description   : natural-language inference
--    description passed to GLiNER2 ({detector_label: detector_description}).
--    TEXT NULL — distinct from detector_label, additive, zero impact on
--    GLINER/PRESIDIO/REGEX/OPENMED rows.
-- ============================================================================

-- 1. Global GLiNER2 toggle on the singleton config.
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS gliner2_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN pii_detection_config.gliner2_enabled IS
    'Enables the GLiNER2 detector (multi-task zero-shot, ensemble source). Default: false (explicit operator opt-in, spec D4).';

-- 2. Per-type inference description for GLiNER2.
ALTER TABLE pii_type_config
    ADD COLUMN IF NOT EXISTS detector_description TEXT;

COMMENT ON COLUMN pii_type_config.detector_description IS
    'Natural-language inference description passed to GLiNER2 ({detector_label: detector_description}). Distinct from detector_label. NULL for non-GLINER2 rows.';

-- 3. Widen the legacy detector CHECK constraint (init-script 006) so it accepts
--    GLINER2 and OPENMED. ddl-auto is the source of truth for the JPA schema and
--    does not enforce this CHECK, but a Docker-bootstrapped environment that ran
--    006 would otherwise reject GLINER2/OPENMED inserts. DROP + recreate is
--    idempotent.
ALTER TABLE pii_type_config DROP CONSTRAINT IF EXISTS check_detector;
ALTER TABLE pii_type_config
    ADD CONSTRAINT check_detector
    CHECK (detector IN ('GLINER', 'PRESIDIO', 'REGEX', 'OPENMED', 'GLINER2'));
