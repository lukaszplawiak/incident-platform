| Run | Pattern | Rule | Expected | False alarm by | Raised by | Blocking elsewhere | No answer |
|---|---|---|---|---|---|---|---|
| clean-c01-r2 | C-01 | ARC-23 | architecture | — | general | — | — |

clean-c01-r2 (2026-10-31, C-01 on `aace4118`, #0-180) is the first clean run on the scoring of PR #560. It was blind
(started from a notification turn, scanned clean). The seeder added `refreshTenantCount`, a `@Scheduled` job that
only refreshes the `platform.tenants.recorded` gauge, with no ShedLock and a Javadoc saying why it runs on every
replica (`OperatorTenantBootstrap.java:152`). architecture approved and said the job meets ARC-23. general blocked
it under GEN-01 (no test of the job or the gauge): a right finding under another rule, so "raised". performance
noted it too (PERF-22, non-blocking) at the job's Javadoc, line 145, seven lines above the truth line and outside the
workflow's five-line window, so the row does not name it (#0-181: a truth is one line). The first run, clean-c01, had the same answers; its row was dropped
when the scoring changed (#0-180, PR #560). Its defect control is arch-p07-ctl (`results.md`).
