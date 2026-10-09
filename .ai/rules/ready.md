# Definition of ready

Owned by the maintainer; agents never edit it. A backlog item gets `**Autopilot:** ready` only from the
owner, and only when everything below holds. The `/ready` skill (and the `ready-checker` agent behind it)
checks an item against this list and drafts what is missing; it never sets `ready` itself.

Most failures of an unattended run are decided before it starts: an item that a human would have to
clarify in round 3 should have been clarified before the picker ever saw it.

## An item is ready when

1. **Numbered acceptance criteria** (`AC1.`, `AC2.`, …), each checkable by a test or a CI check script
   (`.ai/rules/acceptance.md`) — not by a command someone runs by hand. "Works correctly", "is robust",
   "is clean" are not criteria.
2. **No open decision.** The item's `design` questions are answered in the item or an ADR. An item of type
   `design` is never ready as such: the decision is taken first (by the owner, recorded in an ADR), then
   the implementation becomes a ready item.
3. **No irreversible step left to the autopilot.** Any of these makes an item `Risk: high` (owner merges)
   or not ready at all:
   - a destructive or contract-breaking migration (drop, rename, narrowing, a `NOT NULL` without
     default on an existing table);
   - a change to an API or Kafka event contract another service or the frontend consumes;
   - a change to authentication, the security filter chains, tenant isolation, the platform operator API
     or tenant status;
   - a new dependency, Maven plugin or repository, external service, or GitHub workflow;
   - anything in `.ai/rules/`, `.claude/`, `.github/`, `architecture-tests/`.
4. **Small enough for one PR**: one service plus `shared` at most, no more than about 400 changed
   production lines expected, at most 3 review rounds plausible. Bigger items are split first.
5. **Dependencies named** (`**Depends on:**`) and all of them `Done`.
6. **No conflict with an ADR**, or the ADR that changes it exists.
7. **Risk and complexity set** (`**Risk:** low|high`, `**Complexity:** low|medium|high`).
   `Complexity: low` = a local edit in one class or file with an obvious test; only such items may go to
   the optional local-model implementer.
8. **Touches set**: a `**Touches:**` line with the modules (and packages) the change is expected to
   reach, determined from the code (`.ai/rules/planning.md`, "Touches"). It is a measurement, not a
   limit: the autopilot compares it with the architect's plan and with the diff, and records the result
   in the PR. An experiment with an exit criterion (`planning.md`).

## Not ready, on purpose

Items of type `design` (until decided), anything the owner wants to do by hand to learn from, and
`human-only` items such as repository settings (#0-113).

`proposed` items: follow-ups the shipper created from an implementer's "Follow-up needed"
(`planning.md`, "Follow-ups"). They are drafts written by an agent; they become `ready` like any other
item, through `/ready` and the owner's merge — never automatically.

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
