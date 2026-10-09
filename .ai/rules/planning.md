# Planning rules: the execution queue

Owned by the maintainer; agents never edit it. Read by `planner` (and `/plan-backlog`), `ready-checker`,
`architect` (section "Touches"), `implementer` (section "Follow-ups") and `shipper`.

The planner proposes the order in which the autopilot takes backlog items; the owner approves it by
merging the proposal. The planner only proposes: what it may write is limited by a hook, and
`scripts/factory/check-queue.sh` rejects a queue that is inconsistent, whatever the planner thought.
Which item runs next is then decided by code (`scripts/factory/next-item.sh`), from the queue on `main`.

**One item at a time.** The autopilot never works on two items at once (decided 2026-10-09, backlog
#0-114 closed): each item starts from a `main` that contains the previous one, reviewed and merged.

## The queue file

`.ai/plan/queue.md`, human-owned like `.ai/rules/` (CODEOWNERS; the autopilot may not write it). The
part the scripts read is the table between the markers; everything else is for the owner:

```markdown
# Execution queue — <date>

<!-- queue:start -->
| # | Item | Why here |
|---|---|---|
| 1 | #0-108 | ArchUnit first: guards every later item |
| 2 | #0-25 | unblocks #0-28 |
| 3 | #0-28 | needs the @Version of #0-25 |
<!-- queue:end -->

## Items
### 1. #0-108 — <title>
<what the item contains, in 2–3 lines> · Touches: <modules> · Risk / Complexity · Why at this position.
…

## Not queued
- #0-N: <why: design not decided, human-only, waits for an ADR, too big — split proposed, …>

## Changes to BACKLOG.md in this proposal
- #0-N: Touches added / Depends on #0-M added — <one line why>
```

- **Item**: one backlog item per row, `#0-N`. Rows are in execution order; `#` is informational.
- **Strict order**: the autopilot takes the first row that is not done. When that row cannot start (not
  `ready`, a dependency not done, its PR waiting for the owner's merge, BLOCKED), nothing starts — the
  queue does not skip ahead. In shadow mode this means one item per merge, which is the point: the next
  item is built on code the owner has reviewed.
- **Why here**: one line; the reasoning goes into "Items".

## What may be queued

- `ready` items. An item that is `not-ready` or `proposed` may be queued when the planner thinks it
  should come next anyway: `check-queue.sh` warns, and the queue stops at it until the owner runs `/ready`
  on it. Say so in "Why here".
- Never `human-only` items, and never items of type `design` (decide first, in an ADR; then queue the
  implementation item). `check-queue.sh` refuses both.
- Done items may stay in the table (they are skipped); a new proposal drops them.

## Order

Hard rule, checked by code: every `**Depends on:**` that is not done is queued **before** the item that
needs it. Then, in this order of weight:

1. **Unblockers and guards first**: an item others depend on, or one that makes later items safer — a
   test, a CI check, an ArchUnit rule (#0-108), mutation testing (#0-112) — goes before the items it
   protects. A deterministic check pays off on every item after it.
2. **Priority**: `High`, then `Medium`, then `Low`.
3. **Same module together**: consecutive items in one module share context, and each builds on code
   the previous one just left in a reviewed state.
4. **Small before large** when the rest is equal: early feedback on a cheap item is worth more than a
   long item at the head of the queue.
5. **Risk-high items deliberately placed**: each one waits for the owner's merge, and the queue waits with
   it. Put one where the owner expects to have time for it, not at the head before a weekend.

If you find a dependency the backlog does not record (item B cannot work before A), propose a
`**Depends on:**` for B in "Changes to BACKLOG.md" and queue accordingly.

## Touches

`**Touches:**` is the predicted reach of an item, on its own line under the `**Autopilot:**` line:

```
**Touches:** notification-service (notification.queue, notification.scheduler), shared (tenant)
```

- Modules: the Maven modules (`shared`, `service-parent`, `auth-service`, `ingestion-service`,
  `incident-service`, `notification-service`, `escalation-service`, `postmortem-service`,
  `oncall-service`) and the areas `root` (root files: `pom.xml`, `README.md`, `CLAUDE.md`, `Makefile`,
  `.ai/context/`, `architecture-tests/`), `docs`, `k8s`, `docker`, `ci` (`.github/`, `scripts/`).
  Anything else is refused by `check-queue.sh`. Packages in parentheses are for the reader; the scripts
  compare modules only.
- Determine it from the code, not from the title: the classes, endpoints, topics, migrations and tests
  the item names, found with Grep.

**What it is for: a measurement, never a gate.** The same reach is predicted three times, at different
moments and from different knowledge — the item's Touches (at `/ready`, from the item and a search of the
code), the architect's planned modules (just before implementing, after reading the code closely), and
the areas the diff actually reached (`changed-paths.sh`). The autopilot writes all three and a category
into the PR ("Scope"); which pair disagrees says which stage was off (`.ai/rules/audit.md`, "Scope").
Nothing is blocked on a mismatch.

**It is an experiment with an exit.** If after three audits the category `backlog-estimate-off` (the
diff went beyond Touches but stayed within the plan) has never led to a finding, or never changed anything in how items
are prepared, the owner removes the Touches requirement from `ready.md` and keeps the comparison of plan
and diff alone.

## Follow-ups

Work that an item shows is needed, but that is not part of the item:

- **The item cannot meet its own criteria without it** → not a follow-up: the item is BLOCKED (the
  architect or implementer proposes the split) and the owner decides.
- **The item is complete, and the further work completes or extends it** (the next step of the same
  change, a related fix the change made possible or necessary) → a follow-up. The implementer describes
  it in `handoff.md` under "Follow-up needed"; the shipper turns each entry into a backlog item with
  `**Autopilot:** proposed`, `**Depends on:**` and `**Follow-up of:**` the parent, in the parent's PR.
- **An unrelated defect noticed on the way** → "Noticed, not touched" (a proposal in the PR, no item).
- At most 3 follow-ups per item. More means the item was scoped wrong: the implementer stops the item
  instead (BLOCKED, "mis-scoped: <split>").
- A follow-up runs only after the owner approves it: `/ready #0-N` (and the merge of that change). Once
  `ready`, and once its parent is done, it is taken **before** the next queue row (`next-item.sh`), so it
  runs right after the item it came from, without a new plan.

## When to re-plan

The queue is done (`next-item.sh` says so), a BLOCKED item changes what can follow, an audit or an ADR
changes priorities, or several new items became `ready`. Run `/plan-backlog`; the new proposal replaces
the whole queue.

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit of the
planner.
