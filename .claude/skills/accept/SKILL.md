---
name: accept
description: Check your own change against a backlog item's acceptance criteria with the acceptance-reviewer agent, before /ship
argument-hint: "[backlog item, e.g. #0-58]"
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git diff *)
---

Run the `acceptance-reviewer` agent on the current change for backlog item $ARGUMENTS.

1. If $ARGUMENTS names no item (`#0-N`), ask which one; don't guess.
2. Confirm there are changes (`git status`, `git diff HEAD --name-only`); if none, say so and stop.
3. Invoke `acceptance-reviewer` with: "Manual acceptance check of the uncommitted changes (`git diff HEAD`)
   for backlog item $ARGUMENTS. There is no proofs.md: find the evidence in the tests yourself. Write the
   readable report and end with the acceptance JSON."
4. Report its result as it is. If a criterion is not met, say what is missing; don't fix it here.
