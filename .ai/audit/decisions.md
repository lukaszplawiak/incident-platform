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
| R-2026-11-11-pipeline-01 | audit-pipeline (preflight) | high | modify | BACKLOG #0-194 Priority High + AC2 (chore/audit-2026-11-11-pipeline) | AC2 names no mechanism (a file cannot carry its own hash); #0-194's analysis decides it | 0 runs that pass the preflight and then stop for a missing tool or a refused allowed command form (baseline 2: #0-192, #0-197 20261106-214213); #0-194 Done |
| R-2026-11-11-pipeline-02 | audit-pipeline (audit-data.sh) | medium | accept | BACKLOG #0-220 (chore/audit-2026-11-11-pipeline) | — | every case's readyPr merged before its run (baseline 1 of 5 wrong); no item with a /ready PR gets null; no case whose only signal is the risk-high NEEDS_HUMAN (baseline 1) |
| R-2026-11-11-pipeline-03 | audit-pipeline (ready-checker) | medium | accept | `.claude/agents/ready-checker.md` Report point 9 (chore/audit-2026-11-11-pipeline) | — | 0 items BLOCKED at plan/implement for a false premise (baseline 1, #581); every /ready PR's Ready check gives a source or `cannot tell` per premise (baseline 0 of 3) |
