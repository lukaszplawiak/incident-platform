# ADR-0010: Bulk UPDATEs flush before they clear

- **Status:** Accepted
- **Backlog:** #0-83
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

(found in #0-83): `@Modifying(clearAutomatically = true)` must
come with `flushAutomatically = true`. Hibernate flushes before a JPQL bulk statement only pending changes
of the tables it touches, so an earlier change to another table in the same transaction (an outbox INSERT)
is silently discarded by the clear.
