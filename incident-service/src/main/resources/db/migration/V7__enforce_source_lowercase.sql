-- Enforces that the source column on incidents always stores lowercase values,
-- making the normalisation already performed by UnifiedAlertDto's compact
-- constructor a database-level guarantee rather than a single code-path
-- convention.
--
-- Background:
-- UnifiedAlertDto (shared module) normalises source to lowercase in its
-- compact constructor before the value travels through Kafka to incident-service
-- and is persisted. This means every row inserted through the normal alert
-- ingestion path already has a lowercase source.
--
-- The IncidentSpecification.withFilter() filter pushes LOWER(source) to the
-- database and also lowercases the filter parameter in Java, making the
-- filter case-insensitive at the SQL level. Together these two changes
-- (this migration + the IncidentSpecification fix) form a two-layer defence:
--
--   Layer 1 — application: UnifiedAlertDto normalises on write;
--             IncidentSpecification normalises on read (LOWER in SQL).
--   Layer 2 — database:    CHECK (source = lower(source)) rejects any INSERT
--             or UPDATE that would store a non-lowercase value, regardless
--             of which code path produced it (direct SQL, data imports,
--             test fixtures, future integrations).
--
-- PostgreSQL validates CHECK constraints against all existing rows at
-- ADD CONSTRAINT time. If any existing row has source != lower(source),
-- this migration will fail loudly — surfacing data that needs a decision
-- rather than silently producing wrong filter results.
--
-- Note: adding a CHECK constraint on an existing table takes an ACCESS
-- EXCLUSIVE lock for the duration of the validation scan. For a large
-- production table, prefer ALTER TABLE ... ADD CONSTRAINT ... NOT VALID
-- followed by ALTER TABLE ... VALIDATE CONSTRAINT ... (two steps with a
-- shorter lock window). For this dev/portfolio environment a single-step
-- ADD CONSTRAINT is used.

ALTER TABLE incidents
    ADD CONSTRAINT chk_source_lowercase
        CHECK (source = lower(source));