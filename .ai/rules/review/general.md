# Review dimension: general (correctness, tests, conventions)

Owned by the maintainer; agents never edit it. Moved from the test-adequacy, conventions and correctness
sections of `.claude/agents/code-reviewer.md` on 2026-10-05; the checks are unchanged, each now has an id.

Read by `review-general` (the whole file, for every change) and by the implementer, which reads this
file in full for every item; the architect's plan only adds emphasis to the rules at risk.

You are an independent reviewer. You did not write the change you are reviewing — review it the way a
careful teammate would, without assuming the implementer's reasoning was correct just because it's
already there. Review strictly against what's actually in this codebase, `CLAUDE.md` and `.ai/`, not
generic best practices that don't apply here.

## 1. Test adequacy

The question is not "is there a test?" but "would a test fail if this change were broken?". CI's coverage
gates (backlog #0-57) only prove lines were executed; judging whether they were checked is this section's
job.

- **GEN-01 Behaviour → test map.** For every behaviour the diff adds or changes (each new branch,
  condition, return value, state transition, query, error path), name the test that would fail if it
  broke, as `Class#method`. A behaviour with no such test is a finding. Ask "if I inverted this
  condition / dropped this line / returned the old value, which test goes red?" — if the answer is none,
  say so.
- **GEN-02 Error paths, not just the happy path.** Exceptions, fallbacks, retries, a conditional UPDATE
  returning 0 rows, empty or missing results, a lookup that times out.
- **GEN-03 Assertions that mean something.** Flag tests whose only check is "did not throw", a bare
  `verify(...)` of a call without checking its arguments or the resulting state, or an assertion that
  would pass whatever the code under test did (asserting a mock's own stubbed value, `isNotNull()` on
  something that can't be null).
- **GEN-04 A real database where a mock would lie.** A new or changed query, entity mapping, constraint,
  `@Version` field or migration needs a Testcontainers test (`postgres:16-alpine`), not a mocked
  repository. Mocked repositories hid four bugs that only a real database showed: a new entity merged
  instead of persisted (#0-47), a `clearAutomatically` UPDATE that detached a token before its lazy `User`
  was read (#0-50), token types the check constraint rejected (#0-51), and an `Optional` query that threw
  once a second row existed (#0-53).
- **GEN-05 Tenant negative tests.** A change to a tenant-scoped query, Kafka consumer or endpoint needs a
  test that tenant B's data is invisible to, or rejected for, tenant A — not only that tenant A sees its
  own. Code that leaks across tenants is a `security` finding; correct code that no test would catch
  breaking is a finding here.
- **GEN-06 A regression test for every fix**, one that fails without the fix. Say which test that is, or
  that none exists.
- **GEN-07 Determinism.** Flag tests that can pass or fail by chance: exact equality between an in-memory
  `Instant` and one read back from Postgres (`TIMESTAMPTZ` keeps microseconds and rounds the rest — this
  made `AuthRepositoryIntegrationTest` fail on about half of CI runs, PR #435), `Thread.sleep` or
  wall-clock timing, relying on row order without `ORDER BY`, shared state between tests.
- **GEN-08 Weakened tests.** A deleted test, a new `@Disabled`, a removed or loosened assertion, a test
  changed to expect the new behaviour: each needs a reason in `handoff.md` that ties it to a changed
  requirement of the item. Without one it is `NEEDS_HUMAN` (`_common.md`) — making a red test green by
  changing the test is the failure this rule exists for.
- **GEN-09** You judge tests by reading them and cannot run them: never say a test passed or that you ran
  it (the autopilot gives you the result of its own run). Don't quote coverage percentages either: CI
  measures them, and a guess from reading is worse than none.

## 2. Conventions

- **GEN-20** Non-obvious decisions have Javadoc explaining *why*, with a backlog reference where one
  exists; a change that reverses an earlier decision says so where the old reasoning lived.
- **GEN-21** No `TODO`/`FIXME` without a `backlog #N` reference.
- **GEN-22** Tests are named `<Class>Test` and live next to their package under `src/test/java`.
- **GEN-23** Commit message(s) follow Conventional Commits with a service scope.
- **GEN-24** The change stays inside the item: unrelated edits (reformatting, drive-by refactors, a
  "while I was here" fix) are findings, even when correct.

## 3. General correctness

- **GEN-30** Wrong behaviour against the item's acceptance criteria or the code's own contract (a branch
  that cannot be reached, an inverted condition, an off-by-one, a null that can occur).
- **GEN-31** Error handling: swallowed exceptions, a `catch` that loses the cause, an error mapped to the
  wrong HTTP status.
- **GEN-32** Concurrency: a check-then-act race on shared state, a missing guard where two writers exist.

Don't restate what a linter or the test suite already catches.

## Output notes

The test adequacy section always includes the behaviour → test map, even when every behaviour is
covered: one line per behaviour, `behaviour — Class#method` or `behaviour — no test`. For a change with
no behaviour (docs, config comments), say that instead of producing an empty map. For each finding:
**What** (file and line), **Why it matters** (what breaks, and for whom), **Suggested fix** (concrete,
not "consider improving this").

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
