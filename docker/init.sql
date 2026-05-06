-- ============================================================
-- init.sql — executed automatically on the first `docker-compose up`
-- Creates the database and application user with appropriate privileges
-- NOTE: Flyway manages the table schema — this file only handles database setup
-- ============================================================

-- Włącz rozszerzenie pgvector (potrzebne dla Security Bonus)
-- Instalujemy teraz żeby nie musieć restartować kontenera później
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";  -- full text search helper

-- ============================================================
-- Notes on permissions (important for Row-Level Security in E#1):
-- incident_app is the application user — it is NOT a superuser
-- this ensures RLS will work correctly once we add it
-- ============================================================

-- Ensure that the incident_app user has the appropriate privileges
-- (it is created by docker-compose via POSTGRES_USER)
GRANT ALL PRIVILEGES ON DATABASE incidentdb TO incident_app;

-- Schema publicly
GRANT ALL ON SCHEMA public TO incident_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL ON TABLES TO incident_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL ON SEQUENCES TO incident_app;

-- ============================================================
-- Flyway tracking table (Spring Boot creates it automatically,
-- but we ensure that the schema exists)
-- ============================================================
-- Spring Boot with Flyway will automatically detect and apply
-- migrations from resources/db/migration/ in each service
