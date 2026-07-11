-- Migration: Create scan_severity_counts table
-- Purpose: Persist aggregated PII severity counts per space for performance optimization
-- Date: 2025-11-24

CREATE TABLE IF NOT EXISTS scan_severity_counts (
    scan_id VARCHAR(255) NOT NULL,
    space_key VARCHAR(255) NOT NULL,
    high_severity_count INTEGER NOT NULL DEFAULT 0,
    medium_severity_count INTEGER NOT NULL DEFAULT 0,
    low_severity_count INTEGER NOT NULL DEFAULT 0,
    
    CONSTRAINT pk_scan_severity_counts 
        PRIMARY KEY (scan_id, space_key),
    
    CONSTRAINT fk_scan_severity_counts_scan 
        FOREIGN KEY (scan_id) 
        REFERENCES scan_checkpoints(scan_id) 
        ON DELETE CASCADE,
    
    CONSTRAINT chk_severity_counts_non_negative 
        CHECK (
            high_severity_count >= 0 AND 
            medium_severity_count >= 0 AND 
            low_severity_count >= 0
        )
);

-- Index for batch queries by scan_id
CREATE INDEX IF NOT EXISTS idx_scan_severity_counts_scan_id 
    ON scan_severity_counts(scan_id);

-- Index for space-specific queries
CREATE INDEX IF NOT EXISTS idx_scan_severity_counts_space_key 
    ON scan_severity_counts(space_key);

-- Comments for documentation
COMMENT ON TABLE scan_severity_counts IS 
    'Aggregated PII severity counts per space and scan. Updated incrementally during scan execution using atomic UPSERT operations for thread-safety.';

COMMENT ON COLUMN scan_severity_counts.scan_id IS 
    'Reference to scan checkpoint. Cascading delete when scan is removed.';

COMMENT ON COLUMN scan_severity_counts.space_key IS 
    'Confluence space identifier';

COMMENT ON COLUMN scan_severity_counts.high_severity_count IS 
    'Number of HIGH severity PII items detected (e.g., CREDIT_CARD, SSN, IBAN)';

COMMENT ON COLUMN scan_severity_counts.medium_severity_count IS 
    'Number of MEDIUM severity PII items detected (e.g., PERSON, EMAIL, PHONE)';

COMMENT ON COLUMN scan_severity_counts.low_severity_count IS 
    'Number of LOW severity PII items detected (e.g., NATIONALITY, GENDER, DATE)';
