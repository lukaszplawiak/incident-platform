---
name: write-adr
description: Write an Architecture Decision Record in .ai/decisions/ from the template, with the next free number, and add it to the ADR index. Used by the architect agent and by the owner.
argument-hint: "[the decision, one line]"
---

Write an ADR for: $ARGUMENTS

1. Next number: the highest `NNNN` in `.ai/decisions/` plus one. File: `.ai/decisions/NNNN-<kebab slug,
   max 6 words>.md`, copied from `.ai/decisions/_template.md`.
2. Fill every section from facts: the backlog item, the classes involved, the existing precedent, the
   alternatives that were actually possible in this codebase and why each lost. No generic pros and cons.
3. Status: `Proposed` (an agent never writes `Accepted`; the owner does). `Reversible:` yes or no — an
   agent writes an ADR only for a reversible decision; an irreversible one stops the item instead.
4. If it changes an earlier decision: `Supersedes ADR-MMMM`, and in that ADR's status line add
   `Superseded by ADR-NNNN` (the only edit an agent makes to an existing ADR).
5. Add a row to the table in `.ai/decisions/README.md` (number, decision, area keywords, backlog).
6. Keep it to one page. Decisions are read by agents with limited context: precise beats complete.
