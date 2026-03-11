-- Optional helper script to provision the minimal table for JDBC checkpoints
-- Business Rule: BR-SCAN-002 - Includes optimistic locking version for preventing concurrent update conflicts
CREATE TABLE IF NOT EXISTS scan_checkpoints (
  scan_id TEXT NOT NULL,
  source_type TEXT NOT NULL,
  source_key TEXT NOT NULL,
  last_processed_content_id TEXT NULL,
  last_processed_attachment_name TEXT NULL,
  status TEXT NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  version BIGINT DEFAULT 0 NOT NULL,
  progress_percentage DOUBLE PRECISION,
  PRIMARY KEY (scan_id, source_type, source_key)
);

-- Constraint to enforce valid source types
ALTER TABLE scan_checkpoints DROP CONSTRAINT IF EXISTS chk_checkpoint_source_type_valid;
ALTER TABLE scan_checkpoints ADD CONSTRAINT chk_checkpoint_source_type_valid
  CHECK (source_type IN ('CONFLUENCE', 'JIRA', 'SHAREPOINT'));

-- Index for filtering by source type (dashboard per-datasource views)
CREATE INDEX IF NOT EXISTS idx_scan_checkpoints_source_type
    ON scan_checkpoints(source_type);

-- Index for latest checkpoint lookup per source
CREATE INDEX IF NOT EXISTS idx_scan_checkpoints_source_key_updated
    ON scan_checkpoints(source_type, source_key, updated_at DESC);

-- Comment explaining the purpose of the version column
COMMENT ON COLUMN scan_checkpoints.version IS 'Optimistic locking version for preventing concurrent update conflicts. Automatically incremented by JPA on each update. Ensures COMPLETED/FAILED states remain immutable.';

-- Comment explaining the purpose of the progress_percentage column
COMMENT ON COLUMN scan_checkpoints.progress_percentage IS 'Percentage of scan completion (0.0 to 100.0). Persisted from ScanResult.analysisProgressPercentage during scan execution.';

-- Comment explaining the purpose of the source_type column
COMMENT ON COLUMN scan_checkpoints.source_type IS 'Discriminator column identifying the datasource type: CONFLUENCE, JIRA, or SHAREPOINT.';
