-- Migration: Create scan_pii_type_counts table
-- Purpose: Persist aggregated PII type occurrence counts per space for performance optimization
-- Date: 2026-06-24

CREATE TABLE IF NOT EXISTS scan_pii_type_counts (
    scan_id VARCHAR(255) NOT NULL,
    space_key VARCHAR(255) NOT NULL,
    pii_type VARCHAR(255) NOT NULL,
    occurrence_count INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT pk_scan_pii_type_counts
        PRIMARY KEY (scan_id, space_key, pii_type),

    CONSTRAINT fk_scan_pii_type_counts_scan
        FOREIGN KEY (scan_id)
        REFERENCES scan_checkpoints(scan_id)
        ON DELETE CASCADE,

    CONSTRAINT chk_pii_type_counts_non_negative
        CHECK (occurrence_count >= 0)
);

-- Index for batch queries by scan_id
CREATE INDEX IF NOT EXISTS idx_scan_pii_type_counts_scan_id
    ON scan_pii_type_counts(scan_id);

-- Index for scan-wide queries filtered by PII type (the PK already covers the (scan_id, space_key) prefix)
CREATE INDEX IF NOT EXISTS idx_scan_pii_type_counts_scan_id_pii_type
    ON scan_pii_type_counts(scan_id, pii_type);

-- Comments for documentation
COMMENT ON TABLE scan_pii_type_counts IS
    'Aggregated PII type occurrence counts per space and scan. Updated incrementally during scan execution using atomic UPSERT operations for thread-safety.';

COMMENT ON COLUMN scan_pii_type_counts.scan_id IS
    'Reference to scan checkpoint. Cascading delete when scan is removed.';

COMMENT ON COLUMN scan_pii_type_counts.space_key IS
    'Confluence space identifier';

COMMENT ON COLUMN scan_pii_type_counts.pii_type IS
    'PII type code (e.g. EMAIL, PHONE_NUMBER, IBAN_CODE)';

COMMENT ON COLUMN scan_pii_type_counts.occurrence_count IS
    'Number of occurrences detected for this PII type in the space during the scan';
