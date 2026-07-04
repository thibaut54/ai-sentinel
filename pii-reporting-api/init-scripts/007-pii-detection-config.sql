-- Create PII Detection Config Table (Singleton Configuration)
-- This table stores global configuration for PII detectors (GLiNER, Presidio, Regex)
-- Single row table with id = 1

CREATE TABLE IF NOT EXISTS pii_detection_config
(
    id                INTEGER PRIMARY KEY,
    gliner_enabled    BOOLEAN                  NOT NULL DEFAULT true,
    presidio_enabled  BOOLEAN                  NOT NULL DEFAULT true,
    regex_enabled     BOOLEAN                  NOT NULL DEFAULT true,
    default_threshold DECIMAL(3, 2)            NOT NULL DEFAULT 0.80,
    nb_of_label_by_pass INTEGER                NOT NULL DEFAULT 35,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by        VARCHAR(255)                      DEFAULT 'system',
    CONSTRAINT check_default_threshold CHECK (default_threshold >= 0.0 AND default_threshold <= 1.0),
    CONSTRAINT check_single_row CHECK (id = 1)
);

-- Add detector_label column for GLiNER natural language labels
ALTER TABLE pii_type_config
ADD COLUMN IF NOT EXISTS detector_label VARCHAR(100);

-- Create index for detector_label lookups
CREATE INDEX IF NOT EXISTS idx_pii_type_config_detector_label 
ON pii_type_config(detector_label);

-- Add comment explaining detector_label purpose
COMMENT ON COLUMN pii_type_config.detector_label IS
    'Detector-specific label used for detection. For GLINER: natural language labels (e.g., "email"). For PRESIDIO/REGEX: may be NULL or same as pii_type.';

-- Add LLM-as-Judge flag for post-detection false positive filtering (spec §1.4).
-- Defaults to false so the MVP rollout has zero behavioral impact until explicitly enabled.
ALTER TABLE pii_detection_config
ADD COLUMN IF NOT EXISTS llm_judge_enabled BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN pii_detection_config.llm_judge_enabled IS
    'Enables the LLM-as-Judge post-filtering stage (Qwen 3.6) that audits GLiNER findings to reduce false positives. Default: false.';

-- Add deterministic format pre-filter flag for post-detection false positive filtering.
-- Defaults to false so the rollout has zero behavioral impact until explicitly enabled.
ALTER TABLE pii_detection_config
ADD COLUMN IF NOT EXISTS postfilter_enabled BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN pii_detection_config.postfilter_enabled IS
    'Enables the deterministic format pre-filter (IP/MAC/IBAN checksum) before the LLM judge. Default: false.';
