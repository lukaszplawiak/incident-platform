# Audit rules

Owned by the maintainer; agents never edit it. Read by every audit agent. An audit measures how the
agents work, rates what it finds, and **recommends** changes; only the owner decides, and only
`/apply-audit` (run by the owner) changes agents or rules.

## Two ratings for every finding

**Severity** — how much the problem costs:

| Severity | When |
|---|---|
| `high` | risk to the correctness or security of `main`: an escaped defect in `security` or `architecture`, systematic escapes in any dimension, a weakened gate; or the pipeline blocked (repeated BLOCKED, no convergence); or a clear cost increase |
| `medium` | a repeated quality or efficiency problem without an escaped defect: false alarms that cost rounds, overlapping reviewers, blocking findings without an impact scenario, cost above the norm |
| `low` | noise and hygiene: excess non-blocking findings, small drifts out of dimension, prompt or rule text that never fires |

**Confidence** — how strong the evidence is:

| Confidence | When |
|---|---|
| `high` | ≥3 cases, at least one confirmed by the owner (`human:*` label) or by a hard fact (revert, `**Escaped from:**`, a seeded defect missed) |
| `medium` | ≥3 cases from pipeline data only, no human confirmation |
| `low` | 1–2 cases. **Never the basis of a recommendation** — report it as an observation |

One exception: a **deterministic contradiction** between the rules, the agents' definitions and their
permissions — an item the rules let through that the pipeline can by construction only stop (#0-42: a
`ready` item whose criteria need `.github/`, which the implementer may not write) — is a hard fact. One
such case is enough for a recommendation of the kind "a missing hard gate" (a check script or a draft
backlog item), with confidence `high`. A change to prompt or rule text still needs ≥3 cases.

A `high` severity with `low` confidence is listed separately as "serious if true — check yourself".

## Findings and recommendations are different things

- A **finding** is what you observed: severity, confidence, description, evidence (PR numbers, verdict
  ids, transcript paths). Many findings need no change.
- A **recommendation** is a concrete edit to one file that answers one or more findings. Only
  recommendations need the owner's decision.

## When a recommendation is allowed

1. Backed by ≥3 cases (confidence `medium` or `high`).
2. **Relaxing** a `security` or `architecture` criterion (fewer blocking findings) needs owner-confirmed
   false alarms (`human:fp-security`, `human:fp-architecture`). Opinion of the auditor is not enough:
   a false alarm costs a round, an escaped cross-tenant leak costs far more.
3. Not a repeat of a recommendation rejected in `.ai/audit/decisions.md`, unless the evidence is new — say
   what is new.
4. Removal is as valuable as addition: every report lists rules and calibration lines that did not fire
   in the last 3 cycles as removal candidates.
5. Each recommendation is a hypothesis with a **metric** that the next audit checks; a recommendation that
   did not move its metric is proposed for rollback.
6. At most **8 recommendations per report**, ordered by severity, then confidence.

## Where a recommendation goes

| Kind | Target |
|---|---|
| universal — how the agent works in any project | the agent definition, `.claude/agents/<name>.md` (the ready-checker, planner, architect, implementer, reviewers, acceptance-reviewer, shipper) |
| a step the owner runs | the skill, `.claude/skills/<name>/SKILL.md` (`/ready`, `/plan-backlog`, `/review`, `/ship`) |
| project-specific — calibration to this codebase | the `## Calibration` section of the rule file the agent reads (`.ai/rules/review/<dimension>.md`, `acceptance.md`, `implementation.md`, `ready.md`, `planning.md`) |
| a missing hard gate | a draft backlog item (a test or CI check beats a prompt line) |

The pipeline audit picks the row for the stage it addresses (its section below); for the `review` stage it
only attributes the case and refers to the reviewers' report, which owns recommendations to the review files —
so one cycle never gives two answers to the same file, and a PR counts once toward that report's ≥3 cases.
When no reviewers' report is written in the cycle (target `pipeline` alone, or no autopilot PR), the pipeline
report lists the review-stage case as an observation for the next reviewers' audit.

Keep files short: a recommendation that would push a calibration section past ~15 lines must also propose
what to remove.

## Format of a recommendation

```markdown
### R-YYYY-MM-DD-NN · <agent> · <SEVERITY> · confidence <CONFIDENCE>
**Finding:** what was observed. Cases: PR #…, #…, #… (verdict ids, transcript paths).
**Target:** <file> → <section> (universal | project-specific)
**Change:**
  + line to add
  - line to remove
**Metric:** what the next audit measures, and the value that means it worked.
**Decision:** [ ] accept  [ ] reject  [ ] modify  [ ] defer
**Owner note:**
```

No decision ticked = `defer`. Never `accept`.

## Signals recorded in every PR

Besides verdicts and owner labels, each autopilot PR records two measurements (the "Review" section of
the PR, written from the run record). They are cheap evidence for findings about the agents that plan.

**Scope** — the item's `**Touches:**`, the architect's planned modules and the areas the diff reached,
with a category (`.ai/rules/planning.md`, "Touches"). A prediction **holds** when the diff stays within
it; predicting more than happened is not a miss.

| Category | What happened | Points at |
|---|---|---|
| `consistent` | the diff stayed within both Touches and the plan | — |
| `backlog-estimate-off` | beyond Touches, within the plan | item preparation: `/ready`, `ready-checker`, the item's text |
| `plan-off` | within Touches, beyond the plan | the architect |
| `implementation-drift` | beyond both, and both predicted the same | the implementer: scope creep, or something nobody foresaw (check `handoff.md` and the follow-ups) |
| `unclear-item` | beyond both, and they predicted different things | the item itself: ambiguous, or too big (`ready.md`) |
| `within-plan` / `diff-outside-plan` | the item had no Touches | as above, two sources only |
| `within-touches` / `diff-outside-touches` | the architect predicted no modules | as above, two sources only |
| `not-measured` | nothing predicted (also every resumed item), or the diff reached no module | — |

One case is noise; a pattern across items is a finding. `backlog-estimate-off` is also the exit
criterion of the Touches experiment (`planning.md`).

**Stop stage** — for a BLOCKED item, `.ai/runs/state.json` (`history[]`, in the audit data) records the
phase that stopped it (`pick`, `plan`, `implement`, `review`, `acceptance`, `ship`) and its draft PR. That
is where the problem was **detected**; where it was **introduced** is the auditor's finding, read from the
draft PR, `handoff.md`, the item's text and the `/ready` PR that made it ready (its "Ready check" section;
`git log -L` on the item in `BACKLOG.md` finds it). A wide gap between the two (introduced in `/ready`,
detected at `implement`) points at the earlier stage, not at the one that stopped the item.

**Rule selection** — the rules the architect listed for the implementer (`plannedRules`), the rule files
the self-check added (`selfCheck`), and the blocking findings of the panel. A blocking finding whose
`ruleId` comes from a non-core file (`PERF-`, `MIG-`, `K8S-`, `DOC-`), was not listed, and whose file the
self-check did not cover is a **selection miss** of the architect (the self-check reads a whole file only
when the plan listed no rule from it, so a file with one listed rule and a missed second one shows up
here). A blocking finding on a listed rule, or on a
core rule (`GEN-`, `ARC-`, `SEC-`), is an **implementation miss**: the implementer had the rule in front
of it. Rules missed repeatedly are candidates for the architect's calibration, or for a test (a rule a
check enforces needs no one to remember it).

## Pipeline audit

The target `pipeline` (backlog #0-121; agent `audit-pipeline`) audits containment across the whole autopilot,
where the `reviewers` target compares the reviewers with each other. One analyst for every stage, not one per
agent: a problem shows up later than it starts, and only the whole chain shows why.

**Cases** are derived by `scripts/factory/audit-data.sh`, never chosen: an autopilot item that was BLOCKED, took
more than one review round, had a scope category that points at a stage (`backlog-estimate-off`, `plan-off`,
`implementation-drift`, `unclear-item`, `diff-outside-plan`, `diff-outside-touches`; `consistent`, `within-*` and
`not-measured` are clean), an acceptance other than ACCEPT, or an owner label `human:fp-*`, `human:missed-*` or
`human:introduced-*`; a BLOCKED run without a PR; and an escaped defect (`**Fixes:** #0-N · **Escaped from:**
<stage>`). Each carries the item's merged `/ready` PR.

**Stages**, in order, with the artifacts that show their output:

| Stage | Who | Artifacts |
|---|---|---|
| `ready` | `/ready`, `ready-checker`, the owner | the item's text and criteria; the `/ready` PR and its "Ready check" |
| `queue` | `/plan-backlog`, planner | `.ai/plan/queue.md` and its PR |
| `pick` | `next-item.sh`, `check-queue.sh`, picker | the run's start; the gates' refusals |
| `plan` | architect | `.ai/work/<item>/progress.md` (the plan line, the rules listed), a Proposed ADR |
| `implement` | implementer | the diff, `handoff.md`, the self-check, the test gate |
| `review` | the panel | the verdicts and rounds in the PR |
| `acceptance` | acceptance-reviewer | the acceptance JSON |
| `ship` | shipper, the owner's merge | the PR body, labels, what reached `main` |

For each case: the stage that **introduced** the problem, the stage that **detected** it, and for every stage in
between exactly one **escape class**:

- **no rule** — nothing in the stage's definition or rules asked for this check;
- **rule not applied** — the rule was there and the stage (agent or owner) did not apply it;
- **rule allowed it** — a rule let it through on purpose (e.g. "Risk: high items are allowed to proceed");
- **no data** — the stage did not have the input that would show it.

A stage that is not meant to catch a class of problem is recorded as "not this stage's job", not as an escape.
The **containment gap** is the number of stages a problem crossed; a wide gap points at the earliest stage.

**Where a recommendation goes**: to the cheapest stage that could have caught it — a deterministic check first
(`next-item.sh`, `check-queue.sh`, a CI check: a draft backlog item), else one line in that stage's definition,
skill or rule file, by the table in "Where a recommendation goes" above. A case whose fix is already on `main`
measures that fix (its metric), and is not a new recommendation.

**Owner labels**: `human:introduced-<stage>` on the PR confirms the analyst's attribution or corrects it (the label
wins). It counts as owner confirmation for confidence, as `human:fp-*` does for the reviewers.

## Seeded defects

Escaped defects are a slow and painful source of truth. The `seed-bugs` workflow plants a known defect
from `.ai/audit/benchmark/patterns.md` into a throwaway branch and runs the panel on it; the result
(caught by whom, missed by whom) counts as a hard fact for confidence. Seeded branches are never pushed
and never merged.

## Calibration

Added only by the owner. Empty until the first audit.
