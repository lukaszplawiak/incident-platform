# #0-189 The on-call schedules' optional-status query has no repository test

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch `<prefix>/<item-id>-<slug>`; base `<sha>`
- [architect] plan: <one line>; ADR: <none | NNNN (Proposed)>
- [implementer r0] commits <sha>..<sha>; <n> files; tests: <classes>
- [tests r0] PASS | FAIL (exit <code>, log `.ai/runs/<item>/tests-r0.log`)
- [review r1] general APPROVE · architecture APPROVE · security CHANGES_REQUESTED (sec-7f3a) · …
- [implementer r1] fixed sec-7f3a in <sha>
- [acceptance] ACCEPT | REJECT (AC2 missing evidence) | NEEDS_HUMAN (<reason>)
NEXT: <stage>
- [start] started by start-item.sh; branch `refactor/0-189-oncall-optional-status-query-test`; base `14f341685cd5ae69da97b903de56407890e293b7`
NEXT: architect
