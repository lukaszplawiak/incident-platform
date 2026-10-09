# Acceptance rules

Owned by the maintainer; agents never edit it. Read by `acceptance-reviewer` (and `/accept`, when the
owner checks a change of their own).

Acceptance is the business check: does this change do what the backlog item asked, and only that? It
runs after the technical panel approved. It is not a second code review: correctness, security and
style are already judged.

## Evidence

An acceptance criterion `ACn` holds only with evidence of one of these kinds, named precisely:

| Kind | Counts when | Example |
|---|---|---|
| test | a test **asserts** the criterion's observable outcome — not that a method was called, not that nothing threw. The test gate ran it | `TenantSuspensionIT#readOnlyTenantGets503` asserts status 503 and `Retry-After` |
| CI check | a check script under `.github/scripts/` (with its own test) that fails without the change | "jars contain no Testcontainers" as a check script, not a manual `jar tf` |
| code + doc | only for a criterion about documentation or configuration text itself | README section and line |

A command that someone has to run by hand is not evidence in an autopilot run: nobody runs it, and a
command written by the implementer is not run by the gates either (it would be code execution chosen by
the agent under review). A criterion that can only be shown by a command becomes a test or a check
script, or the item is not ready (`ready.md`).

Not evidence: "the implementer says so", a test that exists but asserts something else, a coverage
number, a reviewer's approval, a test whose name matches but whose assertion does not.

For each criterion write `met: true|false`, the evidence, and — when false — what is missing.

## Verdict

- `ACCEPT` — every criterion met, no unrequested behaviour beyond what the item implies.
- `REJECT` — a criterion is not met, or evidence is missing, and the implementer can fix it within the
  item. Give the missing piece as concretely as a failing test would. The autopilot allows **one** fix
  round after a REJECT; a second REJECT stops the item.
- `NEEDS_HUMAN` — any of:
  - the item has `**Risk:** high` (the owner merges it, whatever this review says);
  - a criterion is ambiguous, or two criteria contradict each other or an ADR;
  - meeting a criterion needs a decision the item does not make (a contract change, a destructive
    migration, a new dependency);
  - the change does noticeably more than the item asked (scope creep that a reviewer approved as
    correct but nobody asked for).

## Scope

Compare the diff with the item, not with what you would have done. Unrequested behaviour is a finding
even when it is good; an unrequested refactor is noise in the history and risk without a requirement.
Small consequential changes are fine (a test fixture, a doc line the change made stale).

## Output (JSON, validated by the autopilot)

```json
{
  "item": "#0-58",
  "verdict": "ACCEPT | REJECT | NEEDS_HUMAN",
  "criteria": [ { "id": "AC1", "met": true, "evidence": "IncidentSoftDeleteTest#hidesDeleted asserts …", "missing": null } ],
  "scopeCreep": ["one line each"],
  "reason": "one paragraph for the PR description"
}
```

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
