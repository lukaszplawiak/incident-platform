# Audit decisions ledger

Every audit recommendation and what the owner decided. Written only by `/apply-audit` (run by the
owner); read by the audit agents at the start of each cycle, so that a rejected recommendation is not
proposed again without new evidence, and an accepted one is checked against its metric.

| Id | Agent | Severity | Decision | Applied in | Owner note | Metric check (next audit) |
|---|---|---|---|---|---|---|
| R-2026-11-11-01 | review-docs | medium | accept | `.ai/rules/review/docs.md` Calibration (chore/audit-2026-11-11-reviewers) | — | share of autopilot PRs leaving `.ai/rules/`/CLAUDE.md stale where docs reports the drift with replacement text ≥ 2/3 (baseline 0/3), no NEEDS_HUMAN on an already-scheduled drift |
| R-2026-11-11-02 | review-general | low | accept | `.ai/rules/review/general.md` Calibration (chore/audit-2026-11-11-reviewers) | — | no GEN-24 finding about a deviation from an AC; ≤ 1 finding citing a rule that does not describe it (baseline 4/10) |
| R-2026-11-11-03 | review-security | low | accept | BACKLOG #0-218 (chore/audit-2026-11-11-reviewers) | — | ≥ 1 blind row each for P-25 and P-26 in results.md, security blocks 2/2 at the plant |
| R-2026-11-11-04 | review-performance | low | accept | BACKLOG #0-219 (chore/audit-2026-11-11-reviewers) | — | ≥ 3 blind performance rows and ≥ 1 clean performance row; ≥ 3/3 blocked, 0 false alarms |
