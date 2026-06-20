-- Adds escalation_level as an independent attribute of an incident, separate
-- from its main lifecycle status (OPEN -> ACKNOWLEDGED -> RESOLVED -> CLOSED).
--
-- Previously, ESCALATED was a value in the incidents.status FSM, duplicating
-- state already tracked by escalation-service's EscalationTask entity in its
-- own database. The two were never synchronized: incident-service produced
-- IncidentEscalatedEvent but never consumed it back, so an incident escalated
-- automatically by EscalationScheduler's timeout never had its status updated
-- here. The dashboard could only show ESCALATED if an operator set it manually
-- via REST API — the automatic escalation path was invisible.
--
-- escalation_level makes this an attribute (0 = not escalated, 1 = escalated
-- to SECONDARY, 2 = escalated to MANAGER) that incident-service keeps in sync
-- by consuming its own IncidentEscalatedEvent from incidents.lifecycle —
-- see IncidentEscalationEventConsumer. This removes ESCALATED from the FSM
-- entirely: escalation no longer competes with or blocks the main lifecycle
-- transitions, it annotates them.
ALTER TABLE incidents
    ADD COLUMN escalation_level INTEGER NOT NULL DEFAULT 0;

-- Supports queries like "show all currently escalated incidents" without a
-- full table scan, mirroring the existing tenant-scoped indexing pattern.
CREATE INDEX idx_incidents_tenant_escalation_level
    ON incidents (tenant_id, escalation_level)
    WHERE escalation_level > 0;