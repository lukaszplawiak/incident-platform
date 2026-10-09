---
name: apply-audit
description: Apply the owner's decisions on an audit report — record every decision in the ledger and make exactly the accepted changes on a branch, ready for a PR
argument-hint: "[report file, e.g. .ai/audit/2026-11-03-reviewers.md]"
disable-model-invocation: true
---

Apply the decisions in audit report $ARGUMENTS. Only the owner runs this (it changes agents and rules,
which no agent may change on its own); run it in a normal session, not an autopilot one — the autopilot
settings deny writes to these paths.

1. Read the report. For every recommendation `R-…`, read the decision box: `accept`, `reject`, `modify`
   (the owner note says how) or `defer`. **No box ticked = defer.** Never treat anything as accepted that
   the owner did not tick.
2. Show the owner a table: id, agent, severity, decision, the exact change that will be made (for
   `modify`: the change as the owner note describes it, shown as a diff). Ask for one confirmation for the
   whole table. If the owner changes anything, update the table and ask again.
3. After confirmation, on a new branch `chore/audit-<report date>`:
   - apply each accepted or modified change exactly as shown, to the target file named in the
     recommendation (an agent definition, or the `## Calibration` section of a rule file), each line
     ending with ` (R-…)` so the next audit can find it;
   - for a recommendation that is a draft backlog item, add it to `BACKLOG.md` with the next free number
     and no `**Autopilot:**` line;
   - append one row per recommendation (every decision, including reject and defer) to
     `.ai/audit/decisions.md`;
   - if a calibration section would grow past ~15 lines, stop and ask which lines to remove.
4. Run `scripts/factory-admin/state-reset.sh audit` (resets the audit counter) and remind the owner to delete
   `.ai/STOP` if the audit trigger created it.
5. Give the git commands to commit and push the branch and open the PR (CODEOWNERS will ask the owner to
   approve it — that is intended).
