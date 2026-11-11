# Audit 2026-11-11 · reviewers

Period 2026-10-10 to 2026-11-11. Target `reviewers`: one analyst per dimension (general, architecture, security,
performance, migration, k8s, docs). Every dimension has a result. This is the first reviewers' audit. The
`decisions.md` ledger is empty, and the only earlier report is the pipeline audit of 2026-10-10.

## 1. Summary

| | high | medium | low | total |
|---|---|---|---|---|
| Findings | 0 | 2 | 44 | 46 |
| Recommendations | 0 | 1 | 3 | 4 |

Findings per dimension: general 7, architecture 9, security 7 (1 medium), performance 7, migration 6, k8s 5, docs 5
(1 medium).

Data: 10 autopilot PRs. 8 reached the panel (#482, #572, #576, #583, #584, #586, #587, #590). 2 were BLOCKED
before review (#477, #581). There were also 32 seeded defect rows and 2 clean runs. Owner labels: every `human:*`
label and escape marker in the data is vouched for by the owner, so none was discounted.

**What matters most:** No reviewer let a defect escape. No reviewer cost a round on a real PR, and every seeded plant
in every dimension was caught. The two problems are these. docs did not report `.ai/rules/` text that three PRs left
stale (the owner fixed it by hand, R-2026-11-11-01). And the reviewers are barely measured on the class of change the
autopilot actually ships (security chains, Deployments, entities): their APPROVEs rest on owner `human:agree` for half
the PRs and on nothing else (R-2026-11-11-03, -04).

## 2. Metrics per agent

The previous report (2026-10-10, pipeline) had no per-reviewer metrics. Its one review-stage case, `human:fp-docs` on
PR #482, is not among this cycle's verified labels. So every value below is a baseline ("—" = no previous value).

| Reviewer | PRs on panel | Escaped | False alarms (owner) | Extra rounds | Blocked items | Out of dim. | Overlap | Seeded caught / missed | Seeded false alarms | Previous |
|---|---|---|---|---|---|---|---|---|---|---|
| general | 8 | 0 | 0 | 0 | 0 | 0 | 3 | 3 / 0 (1 blocking miss, fixed by #0-115) | 0 | — |
| architecture | 8 | 0 | 0 | 0 | 0 | 0 | 3 | 7 / 0 (2 upper bound) | 0 | — |
| security | 8 | 0 | 0 | 0 | 0 | 2 | 4 | 10 / 0 (4 told) | 0 (C-03 has no host) | — |
| performance | 8 | 0 | 0 | 0 | 0 | 0 | 0 | 3 / 0 (all pre-blind) | 0 (no clean pattern) | — |
| migration | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2 / 0 | 0 | — |
| k8s | 1 (#482) | 0 | 0 | 0 | 0 | 0 | 0 | 2 / 0 | 0 (no clean pattern) | — |
| docs | 8 | 0 | 0 | 0 | 0 | 0 | 1 | 3 / 0 | 0 | — (prev. `human:fp-docs` #482 no longer verified) |

Other counts. general made 0 blocking and 10 non-blocking findings, and 4 of those cited the wrong rule. migration was
correctly left off every panel: no diff touched a migration or an `@Entity`/`@Column` line. k8s was correctly left off
7 panels. Verified `human:agree`: #482, #572, #576, #583.

## 3. Previous recommendations

The `decisions.md` ledger has no accepted recommendation. The fixes already on `main` that the analysts measured:

| Fix | Metric | Result |
|---|---|---|
| #0-115, GEN-08's Check line (a removed assertion is weakened even when other tests pin the behaviour) | seeded P-15 is blocked by general under GEN-08 | **worked**. gen-after-p15 did not block. gen-after-p15-r2 answered NEEDS_HUMAN under GEN-08. The two runs differ only in the rule text |
| #0-187, SEC-31's Check line | a blind rerun of P-04 is blocked under SEC-31 at the plant | **worked**, on one rerun. sec-p04-blind did not block. sec-p04-blind-r2 blocked |
| #0-182, the k8s reviewer reads manifests and never renders them (642e5f82) | no k8s verdict lists a render or validation as unverified | **too early**. The only k8s verdict (#482) predates the fix |
| #0-160 / #0-182, `_common.md` on the gate artifact and rendering | architecture `unverified` holds no gate or render | **worked** so far: #572 to #590 have an empty `unverified` |
| DOC-10 rewrite, 2026-10-27 (an accepted ADR's Context is not current text) | no DOC-10 finding on an ADR Context | **worked** so far: none since. Two cycles more before calling it settled |

The pipeline fixes #0-122 and #0-125 belong to the next pipeline audit. One possibly relevant fact: #0-199 was BLOCKED
at `plan` (PR #581), and its cause was a false review premise (Observation 1), not a protected path.

## 4. Recommendations for decision

There are 4 recommendations, under the limit of 8, so nothing was dropped. The analysts' ids are in parentheses. Two
changes were made to the analysts' drafts:
- R-2026-11-11-03 was retargeted from a direct edit of `.ai/audit/benchmark/patterns.md` to a draft backlog item.
  audit.md's target table has no row for the benchmark, and a missing measure is a missing gate.
- docs' calibration line also replaces the "Empty until the first audit" sentence, as general's does.

### R-2026-11-11-01 · review-docs · MEDIUM · confidence medium
**Finding:** review-docs did not report a level-A drift in `.ai/rules/review/security.md` that the diff made stale.
general, architecture and security reported it instead, outside their own dimension. The owner fixed it after merge
(5861309d, 311315f9). DOC-10's Check (grep each removed name in `.ai/`) would have found it. A literal level A
(`NEEDS_HUMAN`) would have stopped all three items, although each item had already scheduled the owner's edit as an
"After merge (owner)" step. Cases: PR #590 (SEC-14 "from PUBLIC_PATHS until #0-197": general/architecture outOfScope,
security ruleGap), PR #587 (SEC-10/SEC-12, ingestion's walk: general outOfScope), PR #586 ("until #0-196" clauses:
handoff only). (docs-01)
**Target:** `.ai/rules/review/docs.md` → `## Calibration` (project-specific)
**Change:**
  - Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
  + Added only by `/apply-audit` from accepted audit recommendations.
  + - DOC-10 covers `.ai/rules/` too (level A) (R-2026-11-11-01): grep each name or claim the diff removes or makes
  +   untrue (a test's shape, "until #0-N" for the item being done) in `.ai/rules/review/*.md`, not only README,
  +   CLAUDE.md and `.ai/context`.
  + - A level-A drift that the item's text or `handoff.md` already gives the owner as an after-merge step is
  +   non-blocking: report it under DOC-10 with the file:line and the exact replacement text, so the owner can paste it.
  +   A level-A drift that nothing records is `NEEDS_HUMAN`, as section 1 says.
**Metric:** next cycle, take the autopilot PRs whose diff makes a line of `.ai/rules/` or CLAUDE.md stale (checked
against the owner's follow-up commits to those files). Measure the share where docs reports the drift with replacement
text. Baseline 0/3. It worked if the share is ≥ 2/3 and docs raised no `NEEDS_HUMAN` on a drift the item had already
scheduled. Secondary: outOfScope entries about `.ai/rules/` text from general, architecture and security drop toward 0.
**Decision:** [x] accept  [ ] reject  [ ] modify  [ ] defer
**Owner note:**

### R-2026-11-11-02 · review-general · LOW · confidence medium
**Finding:** general cites a GEN rule whose text does not match the finding: 4 of its 10 findings this cycle. GEN-24
(unrelated edits) was cited twice for a deviation from an acceptance criterion. GEN-01 was cited once for a
criterion's wording. GEN-20 was cited once for a test-coverage gap. These misfits distort the audit's ruleId-based
measures (rules that fire, removal candidates, implementation vs selection miss). They also feed the same-round
duplicates with other reviewers. Cases: PR #572 (GEN-24 `IncidentDenyByDefaultTest.java:82`, GEN-01 `:187`), PR #576
(GEN-24 `NotificationDenyByDefaultTest.java:241`), PR #590 (GEN-20 `ApiKeyDenyByDefaultTest.java:561`). (general-01)
**Target:** `.ai/rules/review/general.md` → `## Calibration` (project-specific)
**Change:**
  - Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
  + Added only by `/apply-audit` from accepted audit recommendations.
  + - Which rule a finding cites (R-2026-11-11-02). The code or test not doing what an acceptance criterion says is
  +   GEN-30. GEN-24 is only for an edit no criterion asks for. A behaviour or case no test would catch breaking is
  +   GEN-01. GEN-20 is only for a missing "why" comment. A finding whose text fits no rule's sentence takes
  +   `ruleId: null`.
**Metric:** the next reviewers' audit re-reads general's findings. It worked if no GEN-24 finding is about a deviation
from an acceptance criterion, and at most 1 finding in the cycle cites a rule whose sentence does not describe the
issue (this cycle: 4 of 10).
**Decision:** [x] accept  [ ] reject  [ ] modify  [ ] defer
**Owner note:**

### R-2026-11-11-03 · review-security · LOW · confidence medium
**Finding:** Seven of 8 autopilot PRs this period changed or tested the security chain and route walks (SEC-10/12/14).
Security cited SEC-12 five times and approved all of them. Yet no seeded pattern plants a weakened forked chain
(SEC-12) or an access by id that checks only that the row exists (SEC-13, IDOR). Security's 10 seeded catches cover
SEC-03, 04, 06, 10 and 31 only. So on the class of change the autopilot ships, security is measured only by owner
`human:agree` (4 of 8 PRs). Cases: PR #572, #576, #583, #584, #586, #587, #590 (security r1 verdicts);
`.ai/audit/benchmark/patterns.md` P-01..P-05. (security-01)
**Target:** `BACKLOG.md` → a draft item (a missing measure; the owner adds the patterns and runs them)
**Change:**
  + ### 0-NNN. No seeded pattern measures security on a weakened forked chain or an IDOR
  + **Type:** ci · **Priority:** Low · **Autopilot:** human-only
  + Owner adds to `.ai/audit/benchmark/patterns.md`, defect patterns table:
  + | P-25 | security | SEC-12 | in a `SecurityFilterChain` the change declares or changes, end it in `authenticated()` instead of `authenticatedExceptPurposeTokens()`, or drop its `.cors(...)`; never in a chain the change does not touch |
  + | P-26 | security | SEC-13 | in a read or change by id the change adds or modifies, compare only that the row exists (drop the tenant, team or role comparison) |
  + AC1: one blind `/seed-bugs` row each for P-25 and P-26 in results.md, scanned clean.
**Metric:** the next reviewers' audit has at least one blind row each for P-25 and P-26 in `results.md`, and security
blocks both at the plant. Worked = 2/2 blocked. A miss is a hard fact for the next security.md calibration.
**Decision:** [x] accept  [ ] reject  [ ] modify  [ ] defer
**Owner note:**

### R-2026-11-11-04 · review-performance · LOW · confidence medium
**Finding:** All of performance's seeded evidence comes from three rows of 2026-10-25. They ran before #0-177, when
the panel's prompt named the branch `seed/P-1x-perf-after-p1x` and the base said `(/seed-bugs, not for review)`.
Their transcripts were scanned and the rows stand (ADR-0044 decision 6), but none has been re-run blind. The 8 live
PRs reached no performance-sensitive path, so these rows are this reviewer's only measure. There is new evidence that
a non-blind row can overstate a result: sec-after-p04 (told) was blocked, sec-p04-blind was not (#0-187). There is
also no clean (`C-`) pattern for performance, so its false-alarm rate is unmeasured. Cases: seeded perf-after-p10,
perf-after-p11, perf-after-p12; ADR-0044 decision 6; clean.md (no performance pattern). (performance-01)
**Target:** `BACKLOG.md` → a draft item (a missing measure; the owner runs it)
**Change:**
  + ### 0-NNN. performance's seeded results predate the blind panel, and no clean run measures its false alarms
  + **Type:** ci · **Priority:** Low · **Autopilot:** human-only
  + Re-run P-10, P-11 and P-12 through `/seed-bugs` as it is now: `review/<8 hex>` branch, `Revert "<subject>"` base, `seed-fold.sh`, started from a notification turn, scanned with `scripts/factory-admin/seed-scan.sh` before the rows land.
  + Owner adds one clean pattern for the Weight rule's exception to `.ai/audit/benchmark/patterns.md` (e.g. an unpaged read over a set the change's own code bounds, or a `@Scheduled` job that is one bounded statement, PERF-22). Run it once, alternating with the defect runs.
  + AC1: three blind performance rows in results.md, each scanned clean. AC2: one clean performance row in clean.md.
**Metric:** at the next reviewers' audit there are ≥3 blind performance defect rows and ≥1 clean performance row. It
worked if performance still catches and blocks ≥3/3 blind rows and shows 0 false alarms on the clean row. A lower
blind catch rate is itself the finding the next audit must act on.
**Decision:** [x] accept  [ ] reject  [ ] modify  [ ] defer
**Owner note:**

## 5. Serious if true

None. No finding has high severity. The closest is Observation 1 (medium severity, low confidence). Worth a look by
the owner, because it already cost one BLOCKED run.

## 6. Observations

These rest on 1–2 cases (low confidence), so they carry no recommendation. The next audit checks each one.

1. **security: a non-blocking claim about library behaviour was false, and it became a backlog item** (medium, 2
   cases).
   - PR #576, SEC-12 at `NotificationDenyByDefaultTest.java:278`, said an OPTIONS request "would run its handler for
     an anonymous caller". Spring MVC 6.2 answers OPTIONS itself. The claim was not put in `unverified`, so the
     verifier never saw it.
   - It became #0-199, which was BLOCKED at plan (PR #581) and re-readied in PR #582.
   - PR #572 has a second claim of the same kind (`JwtAuthFilter.java:26`), which became #0-193.
   - The structural point: non-blocking claims bypass the verifier, yet the owner files items from them.
   - Next audit: count library-behaviour claims outside `unverified`, and how many became backlog items.
2. **Same-round duplicates of non-blocking findings** (seen by general, architecture and security):
   - #590 (GEN-20 = SEC-14 at :561; architecture = security at :476)
   - #576 (GEN-24 = architecture = SEC-12 near :241/:278)
   - #583 (architecture = SEC-12 at `DevSecurityConfig.java:45`)
   - #572 (GEN-24 :82 = DOC-20 :83)

   None cost a round. The partners differ, so no single rule boundary explains it. Part of it follows from the
   misfits that R-2026-11-11-02 addresses.
3. **general blocked a correct clean change under GEN-01** in clean-c01-r2 and clean-c01-r4: the `refreshTenantCount`
   job had no test. That is the rule applied as written ("Raised by"), not a false alarm, but in the autopilot it
   would cost a round.
4. **general handed the owner checks it could have run itself**: PR #590 GEN-23 (a `git log` check), and PR #482
   (it did not read `StructuredLoggingGuardTest`).
5. **docs, a possible DOC-11 miss (1 case)**: in PR #583 the behaviour of `buildCommonSecurity` changed, but its
   Javadoc example still calls a two-argument overload that does not exist and ends in `.authenticated()`. The text
   is still on main (`shared/src/main/java/com/incidentplatform/shared/security/SharedSecurityAutoConfiguration.java:345-358`).
   The backlog item drafted for it in the PR is not in BACKLOG.md. Also a DOC-20 misfit on #572.
6. **architecture, 4 of 9 findings with `ruleId: null` and no `ruleGap` entry**: test-only controllers or
   configurations leaking into other test contexts (#590 :476, `AuthApiTestApplication.java:26`) have no ARC rule.
   Whether they need one is the owner's call.
7. **architecture, ARC-02 credit**: architecture's ARC-02 notes on the copies of the route walk (#572, #576, #590) led
   to #0-198 AC3, yet #0-208 cites security's note on #587. Watch whether #0-208 changes what ARC-02 should say about
   test-code copies.
8. **migration, a narrow trigger gap (0 cases, read from code)**: `scripts/factory/changed-paths.sh:64` counts an
   entity change only on `@Entity|@Table|@Column|@Version`. A diff that changes only a relation, `@Enumerated` or
   `@Lob`, or an unannotated field never puts migration on the panel. The smoke test's `validate` catches a missing
   column, but not nullability or length.
9. **performance, PERF-02 and PERF-30 overlap**: P-12 planted a PERF-02 defect and was blocked under PERF-30 (1 case).
   Performance verdicts also never fill `ruleGap`, and leave `round`, `outOfScope` and `unverified` empty in some PRs.
10. **Benchmark blind spots** reported by several analysts. With no pattern, a reviewer's silence on these rules
    measures nothing:
    - no pattern: ARC-10/24/26–29, MIG-01/03/06/07/08, K8S-01/02/04/05/07/09
    - pattern never run: K8S-06 (P-22)
    - no clean pattern: k8s (K8S-08's exceptions) and performance
    - the clean pattern C-03 (SEC-08) has no host

    A finding that architecture or docs is listed under "Also caught by" in another dimension's row cannot be judged
    in or out of dimension without the transcripts, or a ruleId kept per catch.
11. **Data integrity**: no PR body, diff, label or BACKLOG text tried to steer the audit. The "For the owner" blocks
    were treated as data. No transcripts and no PR comments were read: the evidence is the verdict JSON in the PR
    bodies.

## 7. Removal candidates

None qualify: this is cycle 1 of 3 for every dimension, and every Calibration section is empty. Watch list (no hit in
live PRs or seeded runs this cycle):

- **general:** GEN-02, 04, 05, 06, 07, 21, 31, 32.
- **architecture:** ARC-03, 04, 06, 07, 10, 21, 24, 26, 27, 28, 29, 43. A CI check enforces ARC-40/41/42, so they have
  no hits by design. ARC-10/24/26/29 had no diff in their area.
- **security:** SEC-01, 02, 05, 07, 08, 11, 13, 15, 16, 20, 21, 22, 23, 33, 40, 41, 50.
- **performance:**
  - PERF-03, 10, 11, 12, 20, 21, 31.
  - PERF-40 may be behind the catch in arch-after-p09 (not checked).
  - PERF-02 has not fired by id (its own seed was blocked under PERF-30). If that repeats, merge it with PERF-30.
- **migration:** MIG-02, 03, 05, 06, 07, 08. MIG-05 has no valid measurement: P-19's host already met its check.
- **k8s:** K8S-01 to K8S-07, K8S-09. Only K8S-06 has a pattern, and it was never run. CI decides K8S-20 to K8S-24.
- **docs:** DOC-02 and DOC-30. DOC-01 fired only in a seeded run.

## 8. Evidence

Data file: `.ai/runs/audit/audit-data-2026-10-10.json` (verdict JSON in the PR bodies, `verifiedLabels`,
`state.history`). Benchmark: `.ai/audit/benchmark/results.md`, `clean.md`, `patterns.md`. No transcripts were read.
Labels: all verified, none discounted; `unverifiedLabels` and `unverifiedEscapes` are empty, `ownerIsAutopilot` is false.

- **R-2026-11-11-01 (docs):**
  - PRs [#590](../../pulls/590), [#587](../../pulls/587), [#586](../../pulls/586): the general and architecture
    outOfScope entries, security's ruleGap on SEC-14, and the #586 handoff.
  - The owner's fix commits 5861309d (2026-11-05) and 311315f9 (2026-11-07) on `.ai/rules/review/security.md`.
- **R-2026-11-11-02 (general):**
  - PR [#572](../../pulls/572), general r1: GEN-24 `IncidentDenyByDefaultTest.java:82`, GEN-01 `:187`.
  - PR [#576](../../pulls/576), general r1: GEN-24 `NotificationDenyByDefaultTest.java:241`.
  - PR [#590](../../pulls/590), general r1: GEN-20 `ApiKeyDenyByDefaultTest.java:561`.
- **R-2026-11-11-03 (security):**
  - Security r1 verdicts on PRs [#572](../../pulls/572), [#576](../../pulls/576), [#583](../../pulls/583),
    [#584](../../pulls/584), [#586](../../pulls/586), [#587](../../pulls/587), [#590](../../pulls/590).
  - `human:agree` on #572, #576, #583.
  - `patterns.md` P-01..P-05.
- **R-2026-11-11-04 (performance):**
  - Seeded rows perf-after-p10, perf-after-p11, perf-after-p12 (2026-10-25).
  - ADR-0044 decision 6.
  - The contrast between sec-after-p04 and sec-p04-blind (#0-187).
  - `clean.md`: no performance pattern.
- **Previous-fix metrics:**
  - #0-115: gen-after-p15 against gen-after-p15-r2.
  - #0-187: sec-p04-blind against sec-p04-blind-r2.
  - #0-182: commit 642e5f82, and the PR #482 k8s verdict `unverified[0]`.
- **Observation 1:**
  - PR #576 security r1 SEC-12 `:278`.
  - PR [#581](../../pulls/581): BLOCKED at plan, run 20261102-113931.
  - PR [#582](../../pulls/582).
  - PR #572 security r1 `JwtAuthFilter.java:26`, which became BACKLOG #0-193.
  - Commit 9d6b3002.
- **BLOCKED without a reviewer cause:** #0-192, run wf_a2e7e1c8-3d2, `state.history` 2026-11-01T23:34Z, stage
  `review`, no PR. The shipper had no Bash (BACKLOG #0-194).
