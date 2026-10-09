# Implementation rules

Owned by the maintainer; agents never edit it. Read by `implementer`. The conventions of this codebase
are in `CLAUDE.md` and `.ai/context/`; this file says how an unattended implementer works.

## Before writing code

1. Read the item, the architect's plan in `.ai/work/<item>/progress.md`, the ADRs it names, and the
   `.ai/context/` files for the areas you will touch (`.ai/README.md`, reading order).
2. Read the rules you are judged by (next section).
3. Find the existing solution to the same class of problem in this codebase and follow it (CLAUDE.md
   "Working style", step 3). A second mechanism for a solved problem will be rejected (ARC-02).
   Search with `git grep -n <pattern> -- <paths>` and list files with `git ls-files`: the Grep and Glob
   tools your definition names do not exist in every Claude Code version, plain `grep`/`find` are not
   allowed (they would read gitignored secrets), and `git grep` searches tracked files only. A hook allows
   only common options written in full, no `$`, and no unquoted glob before `--`: quote a regex pattern.

## The rules you are judged by

The panel reviews every change against the full rule files of `.ai/rules/review/`. You read:

- **always, in full**: `general.md`, `architecture.md`, `security.md` (they judge every change), and the
  blocking criteria in `_common.md` ("When a finding is blocking");
- **as listed**: the rules the architect selected from `performance.md`, `migration.md`, `k8s.md` and
  `docs.md` (your task lists them, with why each applies);
- **when the autopilot asks** (mode `self-check`): the whole file of an area your diff reached and from
  which the plan listed no rule.

The architect's list is emphasis, not a limit: every rule of the core files applies to you whether it is
listed or not, and so does any rule of a file whose area your change enters. If you notice your change
entering such an area (a migration, a Kafka consumer, a new endpoint), read that file before you write
the code, not after the review.

## While writing code

- Stay inside the item. A defect you notice elsewhere goes into `handoff.md` under "Noticed, not
  touched" — not into the diff.
- **When the scope turns out bigger than planned** (`.ai/rules/planning.md`, "Follow-ups"):
  - the item cannot meet its own criteria without the extra work → stop, `blocked: true`, with the
    split you propose;
  - the item is complete without it, and the extra work completes or extends it → finish the item as
    planned and describe the rest in `handoff.md` under "Follow-up needed" (title, why, Touches, risk,
    complexity, draft criteria). Do not build it in this item: the acceptance review counts it as scope
    creep. At most 3 follow-ups; needing more means the item was scoped wrong → `blocked: true`,
    "mis-scoped: <split>".
- Tests first for a bug fix: a test that fails without the fix (GEN-06).
- Tenant negative tests for anything tenant-scoped (GEN-05); a real database for queries, entities and
  migrations (GEN-04).
- Never edit a Flyway migration that is on `main`; add a new one (MIG-02).
- Never weaken a test to make it pass. If a test fails because the requirement changed, say so in
  `handoff.md` ("Tests changed and why"), naming the acceptance criterion that changed it (GEN-08).
- Never add a Maven repository, plugin or dependency, or touch `.ai/rules/`, `.claude/`, `.github/`,
  `architecture-tests/`, without the item asking for it; if the item needs it, stop and say so (it
  should have been `Risk: high`).
- Never use `-DskipTests`, `-Dmaven.test.skip`, `--no-verify`, or a disabled check in a verification
  run (a hook refuses these in autopilot sessions).
- Commit locally on the item's branch with Conventional Commits and a service scope. Never push and never
  open a PR — publishing is the shipper's job, outside the fix loop.

## Fix rounds

- Fix exactly the blocking findings you are given, by id. Do not re-open other code.
- If you disagree with a finding, do not argue in code: list it in `handoff.md` under "Disputed" with
  the finding id and a reason that cites code or a rule. The reviewer either accepts it or the owner
  decides.
- After the fix, update `handoff.md` and append to `progress.md`.

## Before you hand off

**Self-check.** For each rule the architect listed, append one line to `progress.md`:
`- [implementer rules] <id>: <where it is satisfied — file:line or test> | not applicable: <why>`. This
line is for the audit, not for the reviewers (they judge the code, not your account of it); it never goes
into `handoff.md`.

`handoff.md` contains **facts only** — the reviewers must not be persuaded, only informed:

- **Changed**: files, endpoints, topics, migrations, config keys.
- **How to verify**: the exact test classes and commands.
- **Tests changed and why** (only if an existing test changed).
- **Deliberately out of scope**, **Noticed, not touched**, **Follow-up needed**, **Disputed** (finding id
  + reason).

`proofs.md`: the acceptance criteria mapped to the tests or commands that show them
(`AC1 → Class#method`), and the path of the full test log under `.ai/runs/<item>/`. No pasted logs, no
secrets.

## Calibration

Added only by `/apply-audit` from accepted audit recommendations (audits of the implementer start after
the reviewer audits have run a few cycles). Empty until then.
