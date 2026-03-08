-- Create SharePoint Connection Config Table (Singleton Configuration)
-- This table stores connection settings for the SharePoint integration via Microsoft Graph API
-- Single row table with id = 1

CREATE TABLE IF NOT EXISTS sharepoint_connection_config
(
    id                      INTEGER PRIMARY KEY,
    tenant_id               TEXT                     NOT NULL DEFAULT '',
    client_id               TEXT                     NOT NULL DEFAULT '',
    client_secret_encrypted TEXT                     NOT NULL DEFAULT '',
    enabled                 BOOLEAN                  NOT NULL DEFAULT false,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by              VARCHAR(255)                      DEFAULT 'system',
    CONSTRAINT check_single_sharepoint_row CHECK (id = 1)
);

-- Insert default configuration row
INSERT INTO sharepoint_connection_config (id) VALUES (1) ON CONFLICT DO NOTHING;
