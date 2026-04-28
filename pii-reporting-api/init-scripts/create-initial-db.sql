-- ============================================================================
-- Standalone script : creates the "ai-sentinel-initial" database by cloning
-- the schema of "ai-sentinel". Run ONCE manually (pgAdmin or psql) on the
-- local Postgres instance. Not wired into Docker init-scripts on purpose.
--
-- Prerequisites
--   - Postgres is running on localhost:5433 (see docker-compose-db.yml).
--   - Database "ai-sentinel" already exists with the schema initialized
--     (tables created by 001-008 init scripts and/or by Hibernate ddl-auto).
--   - No active connection on "ai-sentinel" while this script runs (CREATE
--     DATABASE WITH TEMPLATE locks the source DB).
--
-- Usage
--   psql -h localhost -p 5433 -U postgres -d postgres -f create-initial-db.sql
--
-- After running this once, start the backend with the IntelliJ run config
-- "PROD-initial" (DB_NAME=ai-sentinel-initial + classpath:data-old.sql).
-- ============================================================================

-- Drop existing initial DB if you want a clean reset (uncomment if needed).
-- DROP DATABASE IF EXISTS "ai-sentinel-initial";

-- Clone the schema (and any seed data already loaded) of "ai-sentinel".
-- Note : the seed data in pii_type_config will be overwritten on first boot
-- because data-old.sql uses ON CONFLICT DO NOTHING (so existing rows survive).
-- For a strictly clean slate, TRUNCATE pii_type_config after the CREATE.
CREATE DATABASE "ai-sentinel-initial"
    WITH TEMPLATE "ai-sentinel"
         OWNER postgres;

-- Optional : wipe the cloned PII config so data-old.sql seeds it from scratch.
\connect "ai-sentinel-initial"
TRUNCATE TABLE pii_type_config RESTART IDENTITY;
TRUNCATE TABLE pii_detection_config RESTART IDENTITY;
