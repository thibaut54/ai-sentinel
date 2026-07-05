-- ============================================================================
-- PII finding remediation lifecycle & redaction job journal — additive,
-- idempotent migration.
-- ============================================================================
-- The schema is primarily managed by Hibernate ddl-auto
-- (spring.jpa.hibernate.ddl-auto=update); this script keeps Docker-bootstrapped
-- environments consistent and is safe to run multiple times.
--
-- pii_finding_remediation is a projection of the remediation lifecycle of a
-- finding (identity stable across scans, without scanId/offsets). No row means
-- the finding is implicitly PENDING; a row is created on the first transition.
-- pii_redaction_job is the audit journal of mass-redaction jobs.
-- ============================================================================

-- 1. Remediation lifecycle projection.
CREATE TABLE IF NOT EXISTS pii_finding_remediation (
    finding_id VARCHAR(64) PRIMARY KEY,
    scan_id VARCHAR(100) NOT NULL,
    space_key VARCHAR(50) NOT NULL,
    page_id VARCHAR(100) NOT NULL,
    attachment_name VARCHAR(500),
    pii_type VARCHAR(100) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    detector VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    status_reason VARCHAR(500),
    actor VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    redaction_job_id VARCHAR(100)
);

CREATE INDEX IF NOT EXISTS idx_pii_finding_remediation_space_status
    ON pii_finding_remediation(space_key, status);
CREATE INDEX IF NOT EXISTS idx_pii_finding_remediation_page
    ON pii_finding_remediation(page_id);

COMMENT ON TABLE pii_finding_remediation IS
    'Remediation lifecycle projection per finding. finding_id = SHA-256 of (space_key, page_id, attachment_name, detector, pii_type, value_fingerprint); no row = implicitly PENDING.';
COMMENT ON COLUMN pii_finding_remediation.status IS
    'Lifecycle status: PENDING | REDACTED | MANUALLY_HANDLED | FALSE_POSITIVE. REDACTED is terminal.';
COMMENT ON COLUMN pii_finding_remediation.status_reason IS
    'Optional human-readable reason for the last transition. Never contains PII values.';
COMMENT ON COLUMN pii_finding_remediation.redaction_job_id IS
    'Redaction job that produced a REDACTED status, when applicable.';

-- 2. Redaction job journal.
CREATE TABLE IF NOT EXISTS pii_redaction_job (
    id VARCHAR(100) PRIMARY KEY,
    space_key VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    submitted_selection JSONB NOT NULL,
    resolved_finding_ids JSONB NOT NULL,
    processed INTEGER NOT NULL DEFAULT 0,
    total INTEGER NOT NULL DEFAULT 0,
    outcomes JSONB NOT NULL,
    actor VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pii_redaction_job_space_status
    ON pii_redaction_job(space_key, status);

COMMENT ON TABLE pii_redaction_job IS
    'Audit journal of mass-redaction jobs: submitted selection criteria, frozen resolved finding ids, progression and per-finding outcomes. Never contains PII values.';
COMMENT ON COLUMN pii_redaction_job.status IS
    'Job status: RUNNING | COMPLETED | FAILED | INTERRUPTED. RUNNING jobs are marked INTERRUPTED at application boot.';
COMMENT ON COLUMN pii_redaction_job.resolved_finding_ids IS
    'Finding ids frozen at submission; execution never re-evaluates the selection criteria.';
COMMENT ON COLUMN pii_redaction_job.outcomes IS
    'Per-finding outcome map: REDACTED | SKIPPED_STALE | SKIPPED_VALUE_NOT_FOUND | SKIPPED_ATTACHMENT | FAILED.';

-- 3. Mutual exclusion: at most one RUNNING job per space. Partial unique
--    indexes cannot be expressed through JPA annotations, so this index only
--    exists via this script (ddl-auto does not create it).
CREATE UNIQUE INDEX IF NOT EXISTS uq_pii_redaction_job_running_per_space
    ON pii_redaction_job(space_key) WHERE status = 'RUNNING';
