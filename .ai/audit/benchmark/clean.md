| Run | Pattern | Rule | Expected | False alarm by | Raised by | Blocking elsewhere | No answer |
|---|---|---|---|---|---|---|---|
| clean-c01-r2 | C-01 | ARC-23 | architecture | — | general | — | — |
| clean-c01-r4 | C-01 | ARC-23 | architecture | — | general, architecture, docs | — | — |

clean-c01-r2 (2026-10-31, C-01 on `aace4118`, #0-180) is the first clean run on the scoring of PR #560. It was blind
(started from a notification turn, scanned clean). The seeder added `refreshTenantCount`, a `@Scheduled` job that
only refreshes the `platform.tenants.recorded` gauge, with no ShedLock and a Javadoc saying why it runs on every
replica (`OperatorTenantBootstrap.java:152`). architecture approved and said the job meets ARC-23. general blocked
it under GEN-01 (no test of the job or the gauge): a right finding under another rule, so "raised". performance
noted it too (PERF-22, non-blocking) at the job's Javadoc, line 145, seven lines above the truth line and outside the
workflow's five-line window, so the row does not name it (#0-181: a truth is one line). The first run, clean-c01, had the same answers; its row was dropped
when the scoring changed (#0-180, PR #560). Its defect control is arch-p07-ctl (`results.md`).

clean-c01-r4 (2026-10-31, C-01 on `aace4118`) is the first clean run scored on the seeder's whole edit (#0-181,
ADR-0044 decision 8), with the plant compiled first (decision 9). It was blind (started from a notification turn,
scanned clean). The seeder added the same `refreshTenantCount` job and its gauge, in four hunks of
`OperatorTenantBootstrap.java` (80-81, 88, 105-107, 147-164). No dimension stopped it under ARC-23: no false alarm.
general blocked it under GEN-01 at line 157 (no test of the job or the gauge), architecture noted it (ARC-01, line
153) and docs noted the undocumented gauge and properties at line 80, the field's hunk, seventy lines from the
seeder's line 150: on the one-line scoring docs would not be in "Raised by". performance approved with no note this
time, so the PERF-22 note of clean-c01-r2 was not repeated. The run before it, clean-c01-r3, failed and has no row:
the seeder named line 134, the host's own per-replica job `refreshPendingGauge`, not its plant, and the workflow
refused a line outside the edit (#0-181); its leftover branch is #0-190.
