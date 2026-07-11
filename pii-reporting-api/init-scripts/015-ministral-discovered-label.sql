-- ============================================================================
-- MINISTRAL discovered-label inbox — additive, idempotent migration.
-- ============================================================================
-- The schema is primarily managed by Hibernate ddl-auto (this table is declared
-- on DiscoveredLabelEntity). This script keeps Docker-bootstrapped environments
-- consistent and is safe to run multiple times.
--
-- MINISTRAL is an open-vocabulary detector. Labels it emits without a matching
-- pii_type_config row are dropped from findings and collected here so an
-- operator can promote (create a config) or ignore them. Rows carry only the
-- UPPER_SNAKE label and aggregated counts, never any PII value.
-- ============================================================================

CREATE TABLE IF NOT EXISTS ministral_discovered_label (
    id               BIGSERIAL PRIMARY KEY,
    label            VARCHAR(100) NOT NULL UNIQUE,
    occurrence_count BIGINT NOT NULL DEFAULT 0,
    first_seen       TIMESTAMP NOT NULL DEFAULT now(),
    last_seen        TIMESTAMP NOT NULL DEFAULT now(),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX IF NOT EXISTS idx_ministral_discovered_label_status
    ON ministral_discovered_label (status);
