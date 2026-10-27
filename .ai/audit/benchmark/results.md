| Run | Pattern | Rule | Expected | Caught by expected | Blocking | Also caught by | No answer |
|---|---|---|---|---|---|---|---|
| perf-after-p12 | P-12 | PERF-02 | performance | yes | yes | general, architecture, security, docs | — |
| perf-after-p10 | P-10 | PERF-01 | performance | yes | yes | general, architecture, docs | — |
| perf-after-p11 | P-11 | PERF-23 | performance | yes | yes | general, architecture, security | — |
| sec-after-p01 | P-01 | SEC-06 | security | yes (told the pattern, #0-179) | yes | general, architecture, performance | — |
| sec-after-p02 | P-02 | SEC-04 | security | yes | yes | general, architecture | — |
| sec-after-p03 | P-03 | SEC-03 | security | yes (told the pattern, #0-179) | yes | general | — |
| sec-after-p04 | P-04 | SEC-31 | security | yes (told the pattern, #0-179) | yes | general | — |
| sec-after-p05 | P-05 | SEC-10 | security | yes (told the pattern, #0-179) | yes | general, docs | — |
| arch-after-p06 | P-06 | ARC-22 | architecture | yes | yes | general, security, performance, docs | — |
| arch-after-p07 | P-07 | ARC-23 | architecture | yes | yes | general | — |
| arch-after-p08 | P-08 | ARC-25 | architecture | yes | yes | general, docs | — |
| arch-after-p09 | P-09 | ARC-02 | architecture | yes | yes | general, security, performance, docs | — |
| gen-after-p15 | P-15 | GEN-03 | general | yes | no | — | — |
| gen-after-p16 | P-16 | GEN-08 | general | yes | yes | architecture, security | — |
| gen-after-p15-r2 | P-15 | GEN-03 | general | yes | yes | — | — |
| mig-after-p13 | P-13 | MIG-04 | migration | yes | yes | general, docs | — |
| docs-after-p17 | P-17 | DOC-11 | docs | no | no | general, architecture | — |
| docs-after-p18 | P-18 | DOC-01 | docs | yes | yes | general | — |

perf-after-p12 (2026-10-25, P-12 on `47b95294`) was recorded by hand from the run's journal: its Record phase stopped
on an error before writing the row (backlog #0-176). The seeder replaced the paged query of
`OncallScheduleService.getSchedules` with `repository.findAll()`; performance blocked it under PERF-30, not PERF-02;
migration approved.

The `security.md` series (2026-10-26, #0-115) ran P-04 on `aace4118`, the code commit of PR #459. Its merge,
`8ea7a120`, was refused because it changes `CLAUDE.md`, which the panel reads. Only sec-after-p02 was blind. In
sec-after-p01, sec-after-p03, sec-after-p04 and sec-after-p05 every reviewer was also shown the owner's command that
started the run (`/seed-bugs P-0n <sha> sec-after-p0n`). The Workflow tool puts the message that started a workflow
at the head of each of its agents' prompts (backlog #0-179). No reviewer's tool calls or text name a seed, a
pattern, the run or the catalogue. Their thinking is not in the transcripts, so it cannot be checked. Count these
four as caught by a panel that was told the pattern, not as blind results: their "Caught by expected" cell says so.

The `architecture.md` series (2026-10-26, #0-115) was blind. The owner started each run with a message that names
nothing ("dalej"), and the session called the workflow. In each of the four runs, every reviewer's first message
relayed only that word (#0-179's workaround). No reviewer's tool calls or text name a seed, a pattern, the run or
the catalogue. P-07 was planted in `OperatorTenantBootstrap.scheduledReconcile`, which writes shared state, not in
the per-replica `refreshPendingGauge` of the same class. Architecture blocked it as a job that changes shared state
without its lock (ARC-23), not as a missing annotation. P-09 planted a three-attempt `Thread.sleep` loop in
`RemoteApiKeyLookupService`, and architecture named the existing Resilience4j `@Retry` (ARC-02).

The `general.md` series (2026-10-26, #0-115) was blind (each run started by "dalej"). gen-after-p15 is the first
seeded defect the expected dimension saw and let through. The seeder removed
`assertThat(incident.getEscalationLevel()).isEqualTo(2)` from `IncidentCommandServiceTest#recordsLevel`, leaving a
bare `should().save(incident)`. general reported it under GEN-08 as non-blocking, because `IncidentTest#raisesFromZero`
and `IncidentEscalationLevelIntegrationTest#commitsBeforeReturning` still pin the level, and approved. GEN-08 asks for
a reason whatever else covers the behaviour, so the verdict should have been `NEEDS_HUMAN`. In a real PR, CI's
Factory guards would have stopped it ("fewer assertion lines"). In gen-after-p16, general answered `NEEDS_HUMAN` and
listed the bare `@Disabled` as blocking, with the revocation window it left untested as the impact.

gen-after-p15-r2 (2026-10-26, blind) ran P-15 again once GEN-08's "Check:" said that a removed assertion is weakened
even when other tests still pin the behaviour. The seeder planted the same edit at the same line. This time general
answered `NEEDS_HUMAN` and listed it as blocking under GEN-08, naming the missing reason. The two runs differ only in
the rule's text.

mig-after-p13 (2026-10-26, blind, `40bf2ef6`): migration blocked the `NOT NULL` column under MIG-04. Its MIG-01
finding ("V8, but the highest is V32") is an artifact of the old host, where a July migration reads as new. The seeder
appended to the migration with Bash. `guard-migrations.sh` refused the same edit made with Edit in P-19, which has no
row (backlog #0-172's Also).

The `docs.md` series (2026-10-27, #0-115) was blind: every reviewer's first message relayed only the owner's words
("dalej", "1-3 tak, dalej"). docs-after-p17's "no" is the scoring, not the panel. The seeder changed
`Incident.minutesBetween` to throw instead of returning `null` and left its Javadoc ("or null if end hasn't happened
yet"); the ground truth is the code's line, 266. docs blocked it under DOC-11 at line 257, the stale Javadoc, which
is where a DOC-11 finding points. The Record phase counts a finding within 5 lines of the ground truth, so 9 lines
apart read as a miss (backlog #0-181). Read by hand: caught and blocked by docs. The plant also did not compile (the
`static` method reads the field `id`); security and architecture said so, and no test gate runs before the panel
(#0-181 too). docs-after-p18: docs blocked the required key with no default and no README "Step 2" line at the
planted line of ingestion-service's `application.yml`.
