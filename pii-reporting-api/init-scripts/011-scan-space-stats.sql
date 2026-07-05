-- ============================================================================
-- Per-space scan statistics — additive, idempotent migration.
-- ============================================================================
-- These tables back the dashboard space tooltip. The schema is also declared on
-- JPA entities (Hibernate ddl-auto), so this script keeps Docker-bootstrapped
-- environments consistent and is safe to run multiple times.
--
-- Concurrency: attachments of a page are scanned in parallel, so all increments
-- are applied via SQL upsert (INSERT ... ON CONFLICT DO UPDATE SET x = x + delta)
-- against these rows. Row-level locking on the composite primary key serializes
-- concurrent increments without losing updates.
-- ============================================================================

-- Volume and failure counters per (scan, space).
CREATE TABLE IF NOT EXISTS scan_space_stats (
    scan_id              VARCHAR(255) NOT NULL,
    space_key            VARCHAR(255) NOT NULL,
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    pages_scanned        INTEGER      NOT NULL DEFAULT 0,
    pages_failed         INTEGER      NOT NULL DEFAULT 0,
    page_chars           BIGINT       NOT NULL DEFAULT 0,
    attachments_scanned  INTEGER      NOT NULL DEFAULT 0,
    attachments_failed   INTEGER      NOT NULL DEFAULT 0,
    attachment_chars     BIGINT       NOT NULL DEFAULT 0,
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_scan_space_stats PRIMARY KEY (scan_id, space_key)
);

-- Per-detector cumulated busy time and throughput per (scan, space).
CREATE TABLE IF NOT EXISTS scan_detector_stats (
    scan_id          VARCHAR(255) NOT NULL,
    space_key        VARCHAR(255) NOT NULL,
    detector         VARCHAR(64)  NOT NULL,
    busy_ms          BIGINT       NOT NULL DEFAULT 0,
    chars_processed  BIGINT       NOT NULL DEFAULT 0,
    detections       INTEGER      NOT NULL DEFAULT 0,
    discarded        INTEGER      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_scan_detector_stats PRIMARY KEY (scan_id, space_key, detector)
);

-- Additive column for environments where the table predates the discard metric.
ALTER TABLE scan_detector_stats ADD COLUMN IF NOT EXISTS discarded INTEGER NOT NULL DEFAULT 0;
