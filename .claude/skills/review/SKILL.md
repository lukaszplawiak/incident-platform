---
name: review
description: Run one or more of the project's review agents (general, security, performance, migration, k8s, docs, architecture) against the current uncommitted changes, in parallel, verify the claims they could not check, and report their findings
argument-hint: "[1=general 2=security 3=performance 4=migration 5=k8s 6=docs 7=architecture, e.g. 123, 13, 2, 16 — 4/5 always auto-added when relevant; 6 only runs when typed]"
disable-model-invocation: true
allowed-tools: Bash(git status *) Bash(git diff *) Bash(git show origin/main:.ai/rules/protected-paths.md)
---

Review the current uncommitted changes using the project's review agents. This is the manual entry to
the same panel the autopilot runs: the agents, criteria (`.ai/rules/review/`) and verdict format are the
same, so a change you review here is judged exactly as an autopilot change would be.

## Step 1: Parse explicitly requested reviewers from $ARGUMENTS

Argument: $ARGUMENTS

- `1` → `review-general` (correctness, test adequacy, conventions — the former `code-reviewer`'s test and
  conventions sections)
- `2` → `review-security` (tenant isolation, auth, injection, secrets)
- `3` → `review-performance` (N+1, Kafka throughput, concurrency, memory)
- `4` → `review-migration` (Flyway migrations, entity ↔ schema)
- `5` → `review-k8s` (Dockerfile/Deployment/service-parent/kustomize, POM supply chain)
- `6` → `review-docs` (README.md/CLAUDE.md/.ai/ and Javadoc consistency with the diff)
- `7` → `review-architecture` (boundaries, decided patterns, ADRs — the former `code-reviewer`'s
  shared-security and patterns sections)

Read every digit present in $ARGUMENTS, in any order, separator, or duplication.

If $ARGUMENTS is empty (bare `/review`), the explicitly-requested set is `1, 2, 3, 7` (the old default
`1, 2, 3`, now that the old code reviewer is split into general and architecture). `6` is never part of
this default — it only runs when typed explicitly.

If $ARGUMENTS contains characters that aren't `1`–`7` and nothing valid at all, stop and ask which
reviewer(s) were meant instead of guessing.

## Step 2: Confirm there's something to review, and always gate-check 4/5

Run `git status` and `git diff --name-only` (also `git diff --cached --name-only` if anything is staged)
to get the list of changed files. If there are no changes at all, say so and stop.

**Run this check every time, no matter what $ARGUMENTS was:**

- Migration files or entities: does the changed-files list contain a path matching
  `src/main/resources/db/migration/.*\.(sql|java)$`, or does the diff add/change an `@Entity`, `@Table`,
  `@Column` or `@Version`? If yes, add `review-migration`.
- K8s/build files: does the changed-files list contain a path matching
  `(^|/)k8s/|(^|/)Dockerfile(\.dockerignore)?$|(^|/)pom\.xml$|^\.dockerignore$`? If yes, add `review-k8s`.
  (`.dockerignore` decides what reaches every image's build context, and a `Dockerfile.dockerignore`
  replaces it for that Dockerfile's build, so both are reviewed with the Dockerfiles; the autopilot's
  gate, `scripts/factory/changed-paths.sh`, matches them the same way.)

If `4` or `5` was explicitly typed but its check finds no match, note it as skipped in the final report.
`review-docs` (`6`) has no auto-gate here.

**Final set = (reviewers from Step 1) ∪ (review-migration and/or review-k8s, whichever gates matched).**

**And whether the factory's rules apply:** does the changed-files list contain a path of the Owner-only list of
`.ai/rules/protected-paths.md` as it is on `main` (`git show origin/main:.ai/rules/protected-paths.md`, exactly
that command, the entries between the `protected:start`/`protected:end` markers; an entry ending in `/` is a
directory and what is under it)? Read it from `origin/main`, not the working tree: the change under review must
not shorten the list it is checked against. If it cannot be read, or holds no entry between the markers, the
factory's rules apply (fail closed: a wrong "does not apply" is the error that costs). Step 4 tells
`review-security` and `review-architecture` the answer, so whether they read `.ai/rules/review/factory.md` is
decided here, like the gates above, not by each of them (backlog #0-161).

Only that one `git show` is pre-approved: `git show` and `git diff` take `--output=<file>`, which writes
anywhere, and this session has no Bash hook, so a wildcard allow would let text from the change under review
steer it into writing a file the owner's shell runs (review round 2 of #0-161; `git diff *` is #0-165).

## Step 3: Run the test gate on the working tree

Run `scripts/factory/run-tests.sh --working-tree manual 1` — the autopilot's gate (`./mvnw verify` of the
affected modules), on the uncommitted changes. It prints JSON; on `pass` its `artifact` is
`.ai/runs/manual/gate-r1.json`: the tested HEAD, per module the tests and the JaCoCo line coverage, and the
`dependency:tree` file. The reviewers have no Maven and no network, so this file is how they check a claim
about a test, the coverage or a resolved version instead of marking it unverified (backlog #0-160).

- `pass` → go on, and give the artifact's path to every reviewer (Step 4).
- `fixable` → show its `summary` and stop: the change does not build or a test fails, and a reviewer never
  judges code that does not build. Say which modules and the log path.
- `stop` → show the reason (Docker not running, a report that could not be read, the build changed the
  tree) and ask the owner whether to fix that first or to run the panel without the gate; without it, tell
  every reviewer "no test gate ran" instead of naming an artifact.

It runs every time, for the whole set: one `verify` costs minutes, a review built on "unverified" costs a
round. The gate runs the change's build and tests — its test code and POM plugins — on this machine, before
any reviewer has read them, so it is not in `allowed-tools`: Claude Code asks the owner for every run, and on
code the owner did not write (a bot's branch checked out) that prompt is the moment to read it first.

## Step 4: Dispatch the final set

Invoke every reviewer in the final set in the same turn, so they run in parallel. Tell each one: "Manual
review of the uncommitted changes (`git diff HEAD`); round 1; the test gate passed on this working tree, its
artifact: <path> (read it); write the readable report and end with the verdict JSON." To `review-security` and
`review-architecture` add either "the diff touches Owner-only paths (<the matching paths>): also read
`.ai/rules/review/factory.md` and judge them by its `FAC-S`/`FAC-A` rules" or "no Owner-only path: `factory.md`
does not apply". Don't summarize the diff for them — they read it themselves.

## Step 5: Verify what the panel could not

Collect the `unverified` lists of every reviewer's verdict JSON, drop duplicates (same claim, any case or
spacing), and if any claim is left, invoke the `verifier` agent once with: the claims as a numbered JSON list
(`[{"index": 0, "claim": "…"}]`), the commit (`git rev-parse HEAD`, and that the gate ran on the working tree on
top of it) and the gate's artifact path — **never the diff, a file list or your own summary of the change**: it
must not be steerable by the change it checks (backlog #0-160, ADR-0037). No claim → skip it and say so. No
test gate ran → tell it so: every claim that rests on the artifact is then unverifiable.

It reads only `.ai/runs/` and fetches only from its allow-list (`webfetch-allowlist.sh --interactive` refuses
anything else); this session has no allow rule for WebFetch, so Claude Code asks the owner for each fetch, as
for any other. Its answer per claim:
- `verified` — show the source and quote;
- `refuted` — a **blocking** finding, as if a reviewer had raised it (dimension `verification`);
- `unverifiable`, or no answer for a claim, or `verified`/`refuted` without a source and a quote — the owner
  decides (check it, or accept it knowingly), as for a `NEEDS_HUMAN`.

## Step 6: Report results

Present each reviewer's findings under its own heading (`## General review`, `## Security review`,
`## Performance review`, `## Migration review`, `## Kubernetes/build review`, `## Docs review`,
`## Architecture review` — only for the ones that ran), then `## Verification` (one line per claim: verdict,
claim, source or why). Say in one line which gated reviewers were added or skipped. Don't merge or re-rank
findings across reviewers.

Then one line: whether any reviewer raised a **blocking** finding per `.ai/rules/review/_common.md` (tenant
isolation, auth, shared security, startup-breaking migration/build, data loss — and any finding citing a
rule with a concrete impact), a `NEEDS_HUMAN`, a refuted claim or an unverifiable one. That decides whether it's
safe to move on to `/ship`. And one line on whether `factory.md` applied (Step 2) and for which paths, so the
owner can check that decision too.
