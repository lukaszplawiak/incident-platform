---
name: plan-backlog
description: Propose the execution queue of the backlog (.ai/plan/queue.md) with the planner agent, validate it, and let the owner approve or change it before it is committed
argument-hint: "[optional focus, e.g. \"notification-service\" or \"max 8 items\"]"
disable-model-invocation: true
---

Propose the order in which the autopilot works through the backlog. Focus, if any: $ARGUMENTS.
The owner approves the queue by merging it; until then nothing changes for the autopilot.

1. Check the repository: on `main`, clean working tree, up to date with `origin/main` (`git status`,
   `git fetch origin main`, `git log -1 origin/main`). If not, say what is off and stop — don't fix it
   on the owner's behalf.
2. Create the branch `plan/<YYYY-MM-DD>` (`git switch -c plan/$(date +%F)`; add `-2` if it exists).
3. List the open PRs for the planner (`gh pr list --state open --json number,headRefName,isDraft,title`),
   since it has no GitHub access.
4. Invoke the `planner` agent with the focus and that list.
5. Run `scripts/factory/check-queue.sh`. If `valid` is false, give the planner exactly the errors and let
   it fix the queue (at most two times). Still invalid: show the errors to the owner and stop.
6. Show the owner, in this order: the table, the warnings of `check-queue.sh`, the planner's summary,
   and `git diff --stat` plus the `BACKLOG.md` diff (Touches and Depends on lines only — anything else
   changed in `BACKLOG.md` is an error: revert it and say so).
7. Ask the owner to approve, or to change the order, Touches or dependencies. Apply exactly the changes
   asked for, run `check-queue.sh` again, show the table again. Repeat until the owner approves.
8. Commit: `git add .ai/plan/queue.md BACKLOG.md`, `git commit -m "docs(plan): execution queue <date>"`.
   Then give the commands to publish it (CLAUDE.md "Working style"):
   `git push -u origin plan/<date>` and `gh pr create --base main --title "docs(plan): execution queue <date>" --body "<the table>"`.
   The queue takes effect for the autopilot when the PR is merged.
