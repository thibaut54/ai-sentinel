-- ============================================================================
-- Per-type LLM-as-Judge toggle — additive, idempotent migration.
-- ============================================================================
-- The schema is primarily managed by Hibernate ddl-auto (this column is
-- declared on the JPA entity). This script keeps Docker-bootstrapped
-- environments consistent and is safe to run multiple times.
--
-- pii_type_config.llm_judge_enabled : per-type opt-out of the LLM-as-Judge
--    post-filter. BOOLEAN NOT NULL DEFAULT true — the DEFAULT covers existing
--    rows and inserts with an explicit column list (data.sql), so no data.sql
--    change is required. Only effective when the global llm_judge_enabled flag
--    (pii_detection_config) is on.
-- ============================================================================

ALTER TABLE pii_type_config
    ADD COLUMN IF NOT EXISTS llm_judge_enabled BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN pii_type_config.llm_judge_enabled IS
    'Enables the LLM-as-Judge post-filter for this PII type. Default true. Only effective when the global llm_judge_enabled flag is on.';
