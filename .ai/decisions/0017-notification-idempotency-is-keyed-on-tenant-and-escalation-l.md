# ADR-0017: Notification idempotency is keyed on tenant and escalation level

- **Status:** Accepted
- **Backlog:** —
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

The `notification_queue` unique index and the `notification_log` per-channel check are keyed on
incident + tenant + event type + **escalation level** (+ channel for the log). Do not drop the
level: every escalation is an `IncidentEscalatedEvent` for the same incident, and without the
level the level-2 (MANAGER) notification is discarded as a duplicate of level 1. Do not drop the
tenant either — idempotency, like every other query, is tenant-scoped.

- A repeat escalation at the **same** level is deduplicated on purpose.
- Escalation rows written before migration `V5` are stored at level 0 and cannot be corrected, so a
  replay of an old escalation event is not deduplicated; during a rolling deploy, old pods also
  still write level 0 for every event.
- `escalationLevel` is required on `IncidentEscalatedEvent` and must be an integer in
  `1..2` (the two levels `EscalationTask` creates); anything else is dead-lettered, not coerced
  to `0` (`0` means "not an escalation"). Raise `MAX_ESCALATION_LEVEL` in
  notification-service's `IncidentEventConsumer` together with the escalation chain.
