# ADR-0046: What the review rule files claim is checked: their labels, their words and the patterns' rule ids

- **Status:** Accepted
- **Backlog:** #0-184 (from #0-115's `factory.md`; takes in #0-178's "Also")
- **Date:** 2026-10-29
- **Author:** human (owner's decision, option A of the research, drafted in an interactive session)
- **Reversible:** yes — the check is one Factory guards step; removing it brings back the review by hand

## Context

The files of `.ai/rules/review/` make three claims that nothing checked, and each had been wrong or nearly wrong:

- "Enforced by: <check>" tells a reviewer not to review a rule, because a required CI job decides it (`_common.md`).
  Round 2 of `general.md` found such a claim wrong by hand.
- No rule file may name the seeded-defect measure, since a reviewer reads its dimension's file on every run (ADR-0044
  decision 6). `architecture.md` said "a seeded result" through the P-06…P-09 runs.
- A pattern of `.ai/audit/benchmark/patterns.md` cites a rule id. Retiring ARC-20 left P-06…P-08 citing it, and
  nothing failed.

The labels' text has no grammar. A label names a script (`check-k8s-environments.rb (rule 1)`), a job id
(`validate-k8s-manifests`), a Java test or guard (`ApiKeyDenyByDefaultTest`, `StructuredLoggingGuard`), a tool
("JaCoCo's `check`", "Snyk") or a description ("the Docker Compose smoke test"). A label's parentheses explain it,
and they name other things in backticks (`WebFetch`, `Service`). Required jobs can be skipped: build-and-test skips a
Markdown-only pull request and the smoke test runs only for code or infrastructure, and a skipped required job
counts as passed.

## Decision

`.github/scripts/check-rule-claims.rb`, tested by `test-rule-claims.sh`, runs in Factory guards. That job runs on
every pull request, Markdown-only ones too. It reads backticked names only, outside a label's parentheses:

1. **Labels.** Every script (`*.sh`, `*.rb`, `*.mjs`, `*.js`) and every Java test or guard (`*Test`, `*Tests`,
   `*IT`, `*Guard`) a label names exists. An "Enforced by" names at least one script, or a job by its id.
   - A named script counts only when a step that runs it passes all of these:
     - the step is in a job of the ruleset of `main`'s required checks (`rulesets/main.json`, the one list);
     - the workflow runs on `pull_request`;
     - neither the step nor its job has an `if:` that compares the event with anything but `pull_request` without
       an `||`, or that is `false`;
     - neither continues on error (`continue-on-error` absent or `false`);
     - the step names the script on a line that is no comment and no `echo`/`printf`, and is not followed by
       `|| true` or `|| :`;
     - the step does not run `check-factory-guards.sh`, the owner's step.
   - A named job counts only when it is such a job.
   - Otherwise the rule is at most "Backed by".
2. **Words.** No rule file but `factory.md` holds `benchmark`, `seeded`, `seeder`, `seed-bugs`, `seed branch`,
   `patterns.md` or a pattern id `P-<two digits>`. `_common.md` is checked too.
   - The list leaves out `plant`: SEC-33 names build-and-test's "Plant a local config" step.
   - It leaves out a bare `seed` too: seed data is a word a migration rule may need.
   - No line marker exempts a line.
3. **Pattern ids.** Every pattern that is not retired cites an id of its dimension's file, and that id is not
   retired. The two exceptions are `FAC-S` (security's) and `FAC-A` (architecture's), both in `factory.md`, as
   `backlog-autopilot.js`'s `RULE_FILE_OF` maps them.

Any input the check cannot read fails it.

## Alternatives considered

- **A grammar for every label** (`Enforced by: \`<path or job id>\``, anything else fails). It would check every
  label in full, but the owner would rewrite about 30 labels in seven files that #0-115 had just measured. It would
  also lose what today's text says ("rules 4 and 5", "the two known patterns"). The check can grow into this later;
  the way back would cost more.
- **Three checks, one per claim.** Each would be simpler, but all three would parse the same rule files and the same
  rule definitions. #0-184 took #0-178's "Also" in for that reason.
- **A review rule instead of a check.** That is what was in place, and it missed both drifts it was meant to catch.
- **A line marker that exempts a measure word** (like `gitleaks:allow`). `main` needs none, and the marker would be
  an exemption written into the files the reviewers read.
- **Treat any `if:` on a required job as "not every pull request"** (fail closed). Two of the nine "Enforced by"
  labels (ARC-40, ARC-41) are in build-and-test, whose `if:` skips Markdown-only pull requests, and they are right:
  its checks read no Markdown. A condition on the paths a pull request changes cannot be judged from the YAML.

## Consequences

- A renamed or moved script, a required job taken out of the ruleset, a step moved under a push-only `if:` or into
  the owner's step, and a retired id a pattern cites all fail Factory guards on the pull request that makes them.
  The label is then rewritten or the check put back.
- Left to the reviewer and the owner:
  - plain words in a label;
  - a synonym of the measure outside the phrases;
  - a script run by another script;
  - whether a check decides its rule in full;
  - whether a required job gated on changed paths runs for every pull request that could break the rule.
- Two rule gaps are recorded here, not filled: `.ai/rules/` is the owner's to write.
  - `_common.md`'s definition of "Enforced by" does not mention required jobs that are skipped.
  - `factory.md` has no rule for a check that decides whether another rule counts as "Enforced by".
  - If one is filled, its rule should judge what such a check accepts: `if: false`, `continue-on-error`, `|| true`,
    gated jobs.
- `factory.md` stays out of the words rule as a residual (ADR-0044 decision 6). It is read on an Owner-only diff, a
  measure that `/seed-bugs` refuses. #0-115's F1 measured it by hand, so its reviewers were measured too. That
  measure is rare and run by hand, and FAC-A06 names `seed-bugs.js` because it is a workflow `factory.md` reviews.
- `seed-record.sh` refused an `FAC-A` id (#0-185 "Also"). Amended 2026-10-29 (#0-185): it and `seed-bugs.js` now
  take this check's id form (`[A-Z][A-Z0-9]*-[A-Z]?\d+`), so `seed-record.sh` can write a `factory.md` row (a host
  of one is still refused by `seed-branch.sh`). The id form is now written three times (`check-rule-claims.rb`'s
  `ID`, `seed-record.sh`, `seed-bugs.js`), and nothing checks that they agree: a residual.
- A reviewer recognises a violation in any of these:
  - a label rewritten to "Enforced by" without its check moving into a required job;
  - a new exemption from the words rule;
  - a new `if:` or `continue-on-error` on a step that runs an "Enforced by" script;
  - a second list of the required checks.
