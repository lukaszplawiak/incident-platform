---
name: ship-record
description: The format of an autopilot pull request description — summary, acceptance evidence, the full review record in details blocks, non-blocking findings and proposed backlog items. Preloaded into the shipper; not a command.
user-invocable: false
---

# Autopilot PR description

The PR description is the permanent record of the run: `.ai/work/<item>/` is deleted before merge, and
the audit reads verdicts from here. Write it to a file and pass it with `--body-file`.

```markdown
## Summary
<2–5 bullets: what changed and why, from handoff.md "Changed">

Backlog: #<item> — <title>. Autopilot run <runId>. Mode: <ship | shadow | risk-high>.

## Acceptance
| Criterion | Evidence | Met |
|---|---|---|
| AC1 … | `Class#method` asserts … | ✅ |

## Review
<one line per dimension: verdict per round, e.g. "security: CHANGES_REQUESTED (r1) → APPROVE (r2)">
Rounds: <n>. Tests: <PASS, modules, log path is local-only>.
Scope: <`scope.category`> — Touches <`scope.touches`> · plan <`scope.plan`> · diff <`scope.actual`>
<· outside the plan: `scope.outsidePlan`, if any>. A measurement for the audit, not a verdict.
Picked: <`source` from the record: queue row N | follow-up of #P | priority | manual>. Implementer:
<`implementer`>. Rules planned: <`plannedRules`, ids>. Self-check: <`selfCheck` files, or "not needed">.

<details><summary>Verdicts (JSON, one per reviewer and round)</summary>

```json
[ …every verdict object exactly as returned… ]
```
</details>

<details><summary>Acceptance (JSON)</summary>

```json
{ …acceptance verdict… }
```
</details>

## Non-blocking findings
<grouped by dimension; each one line with rule id and file:line. The owner decides which become items.>

## Local second opinion
<only if the record has `local` with `ran: true`: the local model's findings, all non-blocking, one line
each — the audit compares them with the panel over time.>

## Follow-ups created
<only if you created any: one line each, `#0-M — <title> (Follow-up of #<item>, Touches …)`, then:
"Proposed by the implementer, not ready. To run them right after this item: `/ready #0-M` and merge;
once this PR is merged too, the autopilot takes them before the next queue row.">

## Proposed backlog items
<from handoff.md "Noticed, not touched" and reviewers' ruleGap — drafts, not created.>

## Decisions
<ADRs written in this item (Proposed), with one line each.>

## For the owner
- Label this PR after a spot check: `human:agree`, `human:fp-<dimension>` (a blocking finding that was
  wrong), `human:missed-<dimension>` (a defect the panel should have caught). The audit depends on it.
```

Then the attribution footer your session's instructions ask for, if any.

Never paste logs, stack traces with data, secrets or tenant content into the description.
