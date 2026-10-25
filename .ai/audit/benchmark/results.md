| Run | Pattern | Rule | Expected | Caught by expected | Blocking | Also caught by | No answer |
|---|---|---|---|---|---|---|---|
| perf-after-p12 | P-12 | PERF-02 | performance | yes | yes | general, architecture, security, docs | — |

perf-after-p12 (2026-10-25, P-12 on `47b95294`) was recorded by hand from the run's journal: its Record phase stopped
on an error before writing the row (backlog #0-176). The seeder replaced the paged query of
`OncallScheduleService.getSchedules` with `repository.findAll()`; performance blocked it under PERF-30, not PERF-02;
migration approved.
