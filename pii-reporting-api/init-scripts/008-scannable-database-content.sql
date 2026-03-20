-- Create table for generic database content scanning
CREATE TABLE IF NOT EXISTS scannable_database_content (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    content TEXT,
    title VARCHAR(255),
    source_id VARCHAR(255)
);

-- Index for faster lookup by source_id
CREATE INDEX IF NOT EXISTS idx_scannable_database_content_source_id ON scannable_database_content(source_id);
