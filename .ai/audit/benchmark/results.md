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
