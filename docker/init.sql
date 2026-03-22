-- ============================================================
-- init.sql — uruchamiany automatycznie przy pierwszym docker-compose up
-- Tworzy bazę i użytkownika aplikacji z odpowiednimi uprawnieniami
-- UWAGA: Flyway zarządza schematem tabel — tutaj tylko setup bazy
-- ============================================================

-- Włącz rozszerzenie pgvector (potrzebne dla Security Bonus)
-- Instalujemy teraz żeby nie musieć restartować kontenera później
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";  -- full text search helper

-- ============================================================
-- Uwagi do uprawnień (ważne dla Row-Level Security w E#1):
-- incident_app to użytkownik aplikacji — NIE jest superuserem
-- dzięki temu RLS będzie działać poprawnie gdy go dodamy
-- ============================================================

-- Upewnij się że użytkownik incident_app ma odpowiednie uprawnienia
-- (tworzy go docker-compose przez POSTGRES_USER)
GRANT ALL PRIVILEGES ON DATABASE incidentdb TO incident_app;

-- Schema publiczna
GRANT ALL ON SCHEMA public TO incident_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL ON TABLES TO incident_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT ALL ON SEQUENCES TO incident_app;

-- ============================================================
-- Flyway tracking table (Spring Boot tworzy automatycznie,
-- ale upewniamy się że schemat istnieje)
-- ============================================================
-- Spring Boot z Flyway automatycznie wykryje i zastosuje
-- migracje z resources/db/migration/ w każdym serwisie
