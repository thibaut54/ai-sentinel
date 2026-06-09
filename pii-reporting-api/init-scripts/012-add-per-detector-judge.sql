-- ============================================================================
-- Per-detector LLM-judge routing — additive, idempotent migration.
-- ============================================================================
-- Replaces the single global `llm_judge_enabled` toggle (kept as a derived
-- OR of the columns below, maintained by the API) with one judge flag per
-- detector. The set of detectors whose judge flag is ON becomes the
-- `audit_sources` the pii-detector-service submits to the LLM judge.
--
-- The schema is primarily managed by Hibernate ddl-auto (these columns are
-- declared on PiiDetectionConfigEntity). This script keeps Docker-bootstrapped
-- environments consistent and is safe to run multiple times.
--
-- Defaults: FALSE for every detector. Regex/Presidio stay OFF by design
-- (deterministic detectors = ground truth, spec §2.5); the toggle exists in
-- the UI but is opt-in. The pre-filter is intentionally NOT judge-routable.
-- ============================================================================

ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS gliner_judge_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS presidio_judge_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS regex_judge_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS openmed_judge_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE pii_detection_config
    ADD COLUMN IF NOT EXISTS gliner2_judge_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN pii_detection_config.gliner_judge_enabled IS
    'Routes GLiNER findings to the LLM judge. Default: false.';
COMMENT ON COLUMN pii_detection_config.presidio_judge_enabled IS
    'Routes Presidio findings to the LLM judge. Default: false (deterministic = ground truth, spec §2.5).';
COMMENT ON COLUMN pii_detection_config.regex_judge_enabled IS
    'Routes Regex findings to the LLM judge. Default: false (deterministic = ground truth, spec §2.5).';
COMMENT ON COLUMN pii_detection_config.openmed_judge_enabled IS
    'Routes OpenMed findings to the LLM judge. Default: false.';
COMMENT ON COLUMN pii_detection_config.gliner2_judge_enabled IS
    'Routes GLiNER2 findings to the LLM judge. Default: false.';

-- One-time carry-forward: when the legacy global judge was ON, enable the
-- judge for the model detectors (GLiNER / GLiNER2 / OpenMed) so existing
-- "judge enabled" installs keep auditing them. Regex/Presidio stay OFF.
-- Bootstrap-only effect: on a fresh DB the seed row has llm_judge_enabled=false
-- so this is a no-op (the UI now drives the per-detector flags).
UPDATE pii_detection_config
   SET gliner_judge_enabled  = llm_judge_enabled,
       gliner2_judge_enabled = llm_judge_enabled,
       openmed_judge_enabled = llm_judge_enabled
 WHERE id = 1
   AND llm_judge_enabled = TRUE
   AND gliner_judge_enabled = FALSE
   AND gliner2_judge_enabled = FALSE
   AND openmed_judge_enabled = FALSE;
