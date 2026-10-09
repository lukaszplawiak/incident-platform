# Review dimension: docs

Owned by the maintainer; agents never edit it. Sections 2–4 were moved from
`.claude/agents/docs-reviewer.md` on 2026-10-05 (unchanged, now with ids); section 1 (levels) is new.

Read by `review-docs` (the whole file, whenever it runs) and by the implementer: the rules the
architect's plan lists, or the whole file when the change reaches this area (self-check).

You check whether README.md, CLAUDE.md, `.ai/`, Javadoc, comments and API annotations still tell the truth
about the code after this change. You do not review whether the code is correct, nor whether a decision
was right (that is `architecture`): "ADR-0014 says outbox, the code sends directly" is your finding; whether
sending directly is acceptable is not.

Read `.ai/README.md` before checking anything under `.ai/`: its structure section says which directories
exist on purpose and which don't exist yet. A file of an earlier target structure that is missing on
purpose is not a finding.

If the diff is trivial (formatting, a rename with no behavioural or structural change, a test-only
change) and affects nothing described in docs, say so and return `APPROVE`.

## 1. Levels: what a drift costs, and who fixes it

In this repository part of the documentation is configuration for agents: a wrong command in CLAUDE.md
makes every later item fail the same way.

| Level | Covers | A drift is | Fixed by |
|---|---|---|---|
| **A** human-owned | `.ai/rules/**`, `AGENTS.md`, CLAUDE.md outside `agent-editable` blocks, the Service Map in `.ai/context/project.md` | `NEEDS_HUMAN` — propose the exact text in `fix` | the owner |
| **B** agent input, facts | CLAUDE.md `agent-editable` blocks (commands), `.ai/context/*.md` facts, an ADR's status, `.ai/decisions/README.md` index | blocking | implementer |
| **C** contracts | Javadoc of public/protected API whose behaviour changed (`@param`, `@return`, `@throws`), OpenAPI annotations of a changed endpoint, `@ConfigurationProperties` documentation, README "Step 2" local-run templates, README "Infrastructure Hardening" (CLAUDE.md "Security inventory" rule) | blocking when the text contradicts the behaviour | implementer |
| **D** everything else | comments, other README sections, non-public Javadoc | non-blocking — except a comment that contradicts the code next to it, which is blocking | implementer or a backlog item |

A comment that only restates what the code does is non-blocking noise (`DOC-40`); agents tend to
over-comment.

## 2. New facts not reflected anywhere

- **DOC-01** For each of these kinds of change in the diff, confirm a corresponding doc update exists in
  the same diff:
  - a new runnable service → CLAUDE.md's service/port table, the Service Map, README's service list (the
    Deployment in `k8s/base` is `review-k8s`'s job);
  - a new required config key (like `jwt.secret`, `mfa.encryption-key`) → README's local-run templates
    (CLAUDE.md points to "README Step 2");
  - a newly decided architectural pattern → an ADR and `.ai/context/architecture.md`;
  - a new CI rule or structural requirement → `.ai/context/infrastructure.md` ("CI gotchas");
  - a security control added, removed or weakened, or a gap closed → README "Infrastructure Hardening";
  - any other non-obvious decision (something a future reader, human or AI, couldn't reconstruct just by
    reading the changed source) → an ADR.
- **DOC-02** A backlog item finished by the change moved to `BACKLOG-DONE.md` with its PR (the shipper does
  this; flag it if missing at review time only when the item is marked done in the diff).

## 3. Stale references

- **DOC-10** If the diff renames, removes, or changes the value of something docs currently describe (a
  port, a Makefile target, a module name, a command, a config key, a class named in an ADR), grep
  README.md, CLAUDE.md, AGENTS.md and `.ai/` for the old name/value and flag every place it's still
  mentioned as current.
- **DOC-11** Javadoc or a comment next to changed code that now describes the old behaviour.

## 4. Existing contradictions and `.ai/` hygiene

- **DOC-20** While reading the docs touched by sections 2–3, note an *existing* contradiction between
  README.md, CLAUDE.md and `.ai/` unrelated to this diff — as non-blocking, clearly labeled pre-existing,
  so it isn't attributed to the current diff.
- **DOC-30** If `.ai/context/*` or `.ai/README.md` changed: it reads as durable project knowledge
  (architecture, decisions, invariants), not a changelog entry, and it doesn't duplicate what CLAUDE.md
  already states — CLAUDE.md and `.ai/` complement each other, they don't repeat each other.
- **DOC-40** Comments that restate the code, or a Javadoc block on a trivial getter: non-blocking.

## Audit mode

Run by `.claude/workflows/docs-audit.js` over the whole repository instead of a diff: no verdict; a list
of drifts, each with file, level, the proposed correction and a one-line backlog item draft
(`Complexity: low`). The owner decides which become items.

## Output notes

Order by how likely each finding is to mislead someone relying on the docs. For each: **What** (the file
and section/line where the doc should say something different), **What it misleads** (who trusts this doc
and what they'd get wrong — a new contributor following README's local-run steps, a future session reading
CLAUDE.md), **Suggested fix** (the specific line or section, in the style already used in that doc).

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
