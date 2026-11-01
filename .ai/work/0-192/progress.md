# 0-192 notification, oncall and postmortem refuse an unauthenticated caller on every route

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch `fix/0-192-deny-by-default-three-services`; base `055f7a8e541016e14002f479680be8aa636bc819`
NEXT: architect
