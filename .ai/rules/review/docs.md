# Review dimension: docs

Owned by the maintainer; agents never edit it. Sections 2–4 were moved from
`.claude/agents/docs-reviewer.md` on 2026-10-05, each check with an id; section 1 (levels) was added then.
Rewritten as invariants on 2026-10-27 (backlog #0-115): each rule says what is true of the documentation after a
change, and its "Check:" line what to look for in a diff. No id changed its meaning.

Read by `review-docs` (the whole file, whenever it runs) and by the implementer: the rules the
architect's plan lists, or the whole file when the change reaches this area (self-check). For the implementer a rule
is what the documentation must say; for the reviewer, its "Check:" line is where a drift shows. No rule here has a
check behind it, so none carries the labels `_common.md` defines ("Enforced by", "Backed by").

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
| **A** human-owned | the documentation among the paths of the Owner-only list in `.ai/rules/protected-paths.md` (read it as it is on `main`: it is the one list), CLAUDE.md outside its `agent-editable` blocks, the Service Map in `.ai/context/project.md` | `NEEDS_HUMAN` — propose the exact text in `fix` | the owner |
| **B** agent input, facts | CLAUDE.md `agent-editable` blocks (commands), `.ai/context/*.md` facts, an ADR's status, `.ai/decisions/README.md` index | blocking | implementer |
| **C** contracts | Javadoc of public/protected API whose behaviour changed (`@param`, `@return`, `@throws`), OpenAPI annotations of a changed endpoint, `@ConfigurationProperties` documentation, README "Step 2" local-run templates, README "Infrastructure Hardening" (CLAUDE.md "Security inventory" rule) | blocking when the text contradicts the behaviour | implementer |
| **D** everything else | comments, other README sections, non-public Javadoc | non-blocking — except a comment that contradicts the code next to it, which is blocking | implementer or a backlog item |

A comment that only restates what the code does is non-blocking noise (`DOC-40`); agents tend to
over-comment.

## 2. New facts not reflected anywhere

- **DOC-01 A new fact lands in the documentation that owns it, in the same change.** One invariant, several
  homes:
  - a new runnable service → CLAUDE.md's service/port table, the Service Map, README's service list (the
    Deployment in `k8s/base` is `review-k8s`'s job);
  - a new required config key (like `jwt.secret`, `mfa.encryption-key`) → README's local-run templates
    (CLAUDE.md points to "README Step 2");
  - a newly decided architectural pattern → an ADR and `.ai/context/architecture.md`;
  - a new CI rule or structural requirement → `.ai/context/infrastructure.md` ("CI gotchas");
  - a security control added, removed or weakened, or a gap closed → README "Infrastructure Hardening";
  - any other non-obvious decision (something a future reader, human or AI, couldn't reconstruct just by
    reading the changed source) → an ADR.
  Check: each new service, config key without a default, pattern, CI rule, security control or decision in the
  diff, and its home above in the same diff.
- **DOC-02 A backlog item the change finishes is in `BACKLOG-DONE.md` with its PR.** The shipper moves it, so at
  review time this is a finding only when the diff marks the item done and does not move it. Check: an item whose
  status the diff sets to Done.

## 3. Stale references

- **DOC-10 No document describes as current a name or value the change removed.** When the diff renames, removes or
  changes the value of something docs describe (a port, a Makefile target, a module name, a command, a config key, a
  class named in an ADR), no mention of the old one is left as current in README.md, CLAUDE.md, AGENTS.md or `.ai/`.
  The Context and history of an accepted ADR record what was true when it was decided, so they are not current
  text: an old name there is no finding. A change to the decision itself is `architecture`'s (ARC-05). Check: grep
  for each old name or value the diff removes.
- **DOC-11 Javadoc and comments next to changed code describe the new behaviour.** Check: the Javadoc and comments
  of every method or block whose behaviour the diff changes (`@return`, `@throws`, the conditions in the text).

## 4. Existing contradictions and `.ai/` hygiene

- **DOC-20 A contradiction found that this diff did not cause is reported as pre-existing.** It is non-blocking and
  labeled pre-existing, so it is not attributed to the current diff. Check: while reading the docs touched by
  sections 2–3, two of README.md, CLAUDE.md and `.ai/` that disagree on something the diff does not change.
- **DOC-30 `.ai/context/*` and `.ai/README.md` hold durable knowledge, not a changelog.** They state
  architecture, decisions and invariants, and do not repeat what CLAUDE.md already says: the two complement each
  other. Check: a changed `.ai/context/*` or `.ai/README.md`, for text written as an event and for a CLAUDE.md
  statement copied.
- **DOC-40 A comment says what the code cannot.** A comment or Javadoc that only restates the code (a trivial
  getter's Javadoc) is non-blocking noise; what a comment must say is `general`'s GEN-20. Check: new comments that
  repeat the line they describe.

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
