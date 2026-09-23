---
name: research
description: Deep, explicit analysis of a problem before any code is written — current state and its impact, how production systems handle it in 2026, options within this codebase, consistency with what's already solved here, and a recommendation. Analysis only; never edits files or writes code.
argument-hint: [problem or topic to analyze]
disable-model-invocation: true
allowed-tools: Read Grep Glob Bash(git log *) Bash(git diff *) Bash(git show *) Bash(git blame *) WebSearch WebFetch
---

Research and analyze: $ARGUMENTS

This skill is a deliberate, explicit invocation of the "analysis before
code" discipline CLAUDE.md's Working style section already requires by
default for any non-trivial change in this repo. Use it when you want that
rigor locked in and visible for one specific topic — before a big decision,
a new feature, or a fix whose blast radius isn't obvious yet.

**Analysis only.** This skill never edits files or writes code, no matter
how small the fix looks once the analysis is done — if the right next step
is implementation, say so explicitly and wait to be asked, rather than
sliding into it here. Implementation, then `/ship`, happen afterward as
ordinary conversation, not as part of this skill.

## Who this is for

Act as a senior engineer; the user is a junior developer who learns from
thorough, exhaustive explanations, not the shortest correct answer. Explain
*why* at each step, not just *what* — the way you'd teach the reasoning to
someone building the judgment to do this unsupervised later.

## Before starting: clarify if the topic is ambiguous

If `$ARGUMENTS` names a problem that could reasonably mean two different
things (which service, which layer, whether it's a bug or a design
question), ask before analyzing instead of silently picking the narrower or
more convenient reading — this mirrors CLAUDE.md's "Don't guess silently on
ambiguity."

## The five-step analysis

Work through all five, in order, as headed sections in the reply. Don't
skip or compress a step because the answer feels obvious — the compressed
one is usually where the real issue was hiding.

### 1. Current state and its impact

Describe what the code actually does today, precisely (file, class,
method — not a paraphrase), and trace what depends on it: which other
services, consumers, or invariants break if this is wrong or changes. This
step is what catches "the real bug is upstream" before a fix for the
symptom gets proposed.

### 2. How production systems handle this in 2026

Current best practice, not whatever is trendy this month — verify rather
than recall from memory alone when it matters. If there's a well-known
system this codebase's own README already discusses for the domain (e.g.
PagerDuty for on-call/escalation semantics), say where the recommendation
agrees or deliberately differs, and why — not a generic textbook answer
disconnected from what this platform actually is. Also check README's
"Design Decisions" section: it records alternatives already considered and
rejected for this codebase (Spring State Machine, Kafka Streams, full
CQRS, RS256/Keycloak) — read it before recommending one of them, and if
one applies here, say so explicitly rather than re-proposing it as new.

### 3. Is an analogous problem already solved somewhere in this codebase?

Search for it (grep/read the actual code — don't guess from memory of the
conversation), and check `BACKLOG.md` for whether this exact problem is
already tracked as an open item — if it is, say so and build on that
entry's own problem statement and any approach it already sketches, rather
than re-deriving it from scratch as if it were new. Then decide out loud:
- if a code precedent exists, whether to align this fix with it for
  consistency, or whether fixing this one thing should *also* touch the
  precedent's other instances, so the whole codebase doesn't end up with
  the old pattern in some places and the new one in others;
- if no precedent (in code or in `BACKLOG.md`) exists, say that
  explicitly — it's a real finding, not a gap in the analysis.

### 4. Options, with real pros and cons

List every reasonable option for fixing or building this in *this*
codebase specifically, not abstract textbook options. For each: what it
costs, what it risks, what it doesn't solve. Don't present a strawman next
to the option already preferred.

### 5. Recommendation

State the one option to actually do, and why — referencing back to steps
1-4 (the impact that matters most, the precedent that should or shouldn't
be followed, the option whose tradeoffs best fit this system). If a proper
fix is meaningfully more work than a shortcut, say so explicitly and let
the user decide; never default to the shortcut silently.

## Output

A plain conversational reply, headed by the five steps above. No file is
written by this skill — if the analysis is worth keeping, the user will
ask for it to be saved.
