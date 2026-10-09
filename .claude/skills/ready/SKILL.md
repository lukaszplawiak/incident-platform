---
name: ready
description: Prepare a backlog item for the autopilot — check it against the definition of ready, draft numbered acceptance criteria and the Autopilot line, and write them into BACKLOG.md only after the owner confirms
argument-hint: "[backlog item, e.g. #0-25]"
disable-model-invocation: true
---

Prepare backlog item $ARGUMENTS for the autopilot. This is phase 0 work: the quality of every unattended
run is decided here.

1. If $ARGUMENTS names no item, list the five highest-priority open items without an `**Autopilot:**` line
   and ask which one; don't guess.
2. Invoke the `ready-checker` agent on the item. Show its report as it is.
3. Ask the owner, in one message, to confirm or correct: the acceptance criteria (`AC1.` …), `Risk`,
   `Complexity`, `Depends on`, `Touches`, and whether the item is `ready`, `not-ready` or `human-only`. Never choose
   `ready` on the owner's behalf; an unanswered question means `not-ready`.
4. Only after the owner answers: edit that item in `BACKLOG.md` — the `**Autopilot:**` line under the
   Type/Priority/Status line (replace it if it exists, e.g. a follow-up's `proposed`; keep its
   `**Follow-up of:**`), the `**Touches:**` line under it, and an `**Acceptance criteria.**` block with the
   numbered criteria (keep the existing prose `**Acceptance.**` paragraph; a follow-up's draft criteria
   are replaced by the confirmed ones). Touch nothing else in the file.
5. Give the git commands to commit it on a `docs/backlog-ready-<item>` branch (CLAUDE.md "Working style":
   commands ready to paste). `ready` takes effect for the picker only once it is on `main`.
