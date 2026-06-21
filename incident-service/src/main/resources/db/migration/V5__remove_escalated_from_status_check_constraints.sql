-- Removes ESCALATED from the status CHECK constraints on incidents and
-- incident_history, bringing the schema back in sync with IncidentStatus.
--
-- IncidentStatus was narrowed from 5 to 4 values in V4 (escalation became
-- the independent escalation_level attribute, see that migration's comment
-- for the full rationale), but the CHECK constraints added in V1 and V2
-- were left untouched — they still formally permitted 'ESCALATED' as a
-- status value, even though no code path can ever write it anymore
-- (Hibernate only ever serializes the current Java enum's values).
--
-- This was harmless in practice but misleading: anyone reading the schema
-- directly (a DBA, a generated ERD, a hand-written analytics query) would
-- see 'ESCALATED' as a legal status with no indication it's unreachable.
-- It also meant the constraint would have silently accepted a manually
-- inserted 'ESCALATED' row (e.g. from a restored backup or a hand-written
-- migration script) that Hibernate would then fail to deserialize with
-- "No enum constant IncidentStatus.ESCALATED" — the constraint was wider
-- than what was actually safe.
--
-- PostgreSQL validates a new CHECK constraint against all existing rows at
-- ADD CONSTRAINT time — if any row still has status/from_status/to_status
-- = 'ESCALATED', this migration will fail loudly rather than silently
-- corrupting data. That is the correct behaviour here: it surfaces stale
-- data that needs a deliberate decision (migrate it or delete it) rather
-- than this migration making that choice silently.
ALTER TABLE incidents
DROP CONSTRAINT chk_incident_status;
ALTER TABLE incidents
    ADD CONSTRAINT chk_incident_status
        CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'CLOSED'));

ALTER TABLE incident_history
DROP CONSTRAINT chk_history_from_status;
ALTER TABLE incident_history
    ADD CONSTRAINT chk_history_from_status
        CHECK (from_status IS NULL
            OR from_status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'CLOSED'));

ALTER TABLE incident_history
DROP CONSTRAINT chk_history_to_status;
ALTER TABLE incident_history
    ADD CONSTRAINT chk_history_to_status
        CHECK (to_status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'CLOSED'));