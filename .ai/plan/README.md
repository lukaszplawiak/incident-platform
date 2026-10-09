# .ai/plan

The order in which the autopilot works through `BACKLOG.md`.

- `queue.md` — the approved execution queue. Proposed by the `planner` agent through `/plan-backlog`,
  approved by the owner by merging that PR; human-owned (CODEOWNERS, and the autopilot's settings deny
  writing it). The format and the rules for order, `**Touches:**` and follow-ups are in
  `.ai/rules/planning.md`; `scripts/factory/check-queue.sh` validates it (also in CI, "Factory guards").
- While `queue.md` does not exist, the autopilot takes `ready` items by priority. Once it exists, the
  autopilot follows it and nothing else; when it is done, run `/plan-backlog` again.

Which item runs next is computed by `scripts/factory/next-item.sh` from the files on `main`: first a
`ready` follow-up whose parent is done, then the first queue row that is not done — strictly in order:
when that row cannot start, nothing starts. One item at a time.
