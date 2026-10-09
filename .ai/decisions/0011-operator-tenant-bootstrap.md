# ADR-0011: Operator tenant bootstrap

- **Status:** Accepted
- **Backlog:** #0-80, #0-49
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

the operator admin creates the integration, and
the key lives only in Alertmanager's `credentials_file`. Since #0-80 `OperatorTenantBootstrap` also
records the operator tenant's `tenants` row (on a new database V21 runs before any user exists).
Since #0-49 it is a reconciler (`@Scheduled` + ShedLock, ~30 s after start, then hourly), not a
one-shot startup runner: it checks
for an admin who can log in, re-invites through `ResendInviteService` when the invite permanently
failed or expired, never creates a second admin or deletes a user (an unexpected state is an ERROR
for a human), and exports `platform.operator.admin.pending`, alerted by `OperatorAdminNotActivated`.
