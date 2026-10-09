# ADR-0001: Escalation is an attribute, not a lifecycle state

- **Status:** Accepted
- **Backlog:** —
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

`IncidentStatus` has exactly four states: `OPEN → ACKNOWLEDGED → RESOLVED → CLOSED`.
Escalation is tracked separately as `Incident.escalationLevel`, so an incident can be
escalated while `ACKNOWLEDGED`. Do not add an `ESCALATED` status — the level is updated
by `IncidentEscalationEventConsumer` (from `IncidentEscalatedEvent` on
`incidents.lifecycle`) without going through `IncidentFsm`.
