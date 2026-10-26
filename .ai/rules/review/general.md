# Review dimension: general (correctness, tests, conventions)

Owned by the maintainer; agents never edit it. Moved from the test-adequacy, conventions and correctness
sections of `.claude/agents/code-reviewer.md` on 2026-10-05, each check with an id; rewritten as invariants on
2026-10-26 (backlog #0-115): each rule says what is true of the code, and its "Check:" line what to look for in a
diff. No id changed its meaning. GEN-09 is retired: how a reviewer reports tests and coverage is `_common.md`'s.

Read by `review-general` (the whole file, for every change) and by the implementer, which reads this
file in full for every item; the architect's plan only adds emphasis to the rules at risk. For the implementer a rule
is what the code must be; for the reviewer, its "Check:" line is where a violation shows.

**No rule is dropped for being general knowledge** (as in `security.md`): a blocking finding must cite a rule
(`_common.md`). **"Backed by: <check>"** names a check that sees the known form of a rule, not a new one: the rule is
still reviewed, and its "Check:" says what the check misses (`_common.md` defines the two labels).

You are an independent reviewer. You did not write the change you are reviewing: review it the way a
careful teammate would, without assuming the implementer's reasoning was correct just because it's
already there. Review against what is in this codebase, `CLAUDE.md` and `.ai/`, not generic best practices that
do not apply here.

## 1. Test adequacy

The question is not "is there a test?" but "would a test fail if this change were broken?". CI's coverage
gates (backlog #0-57) only prove lines were executed; whether they were checked is this section's job.

- **GEN-01 Every behaviour the change adds or changes has a test that fails when it breaks.** Each new branch,
  condition, return value, state transition, query and error path is pinned by a test, `Class#method`, that goes
  red if the condition is inverted, the line dropped or the old value returned. Backed by: JaCoCo's `check` (60%
  of a module's lines) and diff-cover (60% of the changed lines), which count lines executed, not checked. Check:
  each behaviour of the diff against the tests, asking which one would fail without it (the behaviour → test map
  in the output).
- **GEN-02 Error paths are tested, not only the happy path.** Exceptions, fallbacks, retries, a conditional UPDATE
  returning 0 rows, an empty or missing result, a lookup that times out. Check: each `catch`, fallback, guard and
  `if` on a failure the diff adds, and its test.
- **GEN-03 An assertion checks the outcome.** A test checks the state or the value the code produced, or a call
  with its arguments; not only that nothing threw, a bare `verify(...)`, a mock's own stubbed value, or
  `isNotNull()` on what cannot be null. Check: every new or changed assertion, and whether it would pass whatever
  the code under test did.
- **GEN-04 A query, mapping or migration is tested on a real database.** A new or changed query, entity mapping,
  constraint, `@Version` field or migration has a Testcontainers test (`postgres:16-alpine`), not a mocked
  repository. Mocks hid four bugs only a real database showed: a new entity merged instead of persisted (#0-47), a
  `clearAutomatically` UPDATE that detached a token before its lazy `User` was read (#0-50), token types the check
  constraint rejected (#0-51), and an `Optional` query that threw once a second row existed (#0-53). Check: a
  changed repository, entity or migration, and whether its test starts a container.
- **GEN-05 A tenant-scoped path has a negative test.** A change to a tenant-scoped query, Kafka consumer or
  endpoint has a test that tenant B's data is invisible to, or rejected for, tenant A, not only that tenant A sees
  its own. Code that leaks across tenants is `security`'s; correct code that no test would catch breaking is a
  finding here. Check: the tests of a tenant-scoped change, and whether any uses a second tenant.
- **GEN-06 A fix comes with a test that fails without it.** Check: a change that fixes a bug, and the test that
  would be red on the code before it (name it, or say none exists).
- **GEN-07 A test passes or fails for one reason.** No exact equality between an in-memory `Instant` and one read
  back from Postgres (`TIMESTAMPTZ` keeps microseconds and rounds the rest: `AuthRepositoryIntegrationTest` failed
  on about half of CI runs, PR #435), no `Thread.sleep` or wall-clock timing, no row order without `ORDER BY`, no
  state shared between tests. Check: time comparisons, sleeps, ordering and static or shared fixtures in new tests.
- **GEN-08 A test is weakened only for a changed requirement.** A deleted test, a new `@Disabled`, a removed or
  loosened assertion, or a test changed to expect the new behaviour has a reason tied to a changed acceptance
  criterion: in `handoff.md` for an autopilot item ("Tests changed and why"), in the commit message otherwise.
  Without one it is `NEEDS_HUMAN` (`_common.md`): making a red test green by changing the test is the failure this
  rule exists for. A manual `/review` of uncommitted changes has no commit message yet, so there the owner gives the
  reason.
  Backed by: `check-factory-guards.sh` (a deleted test file, a new `@Disabled`, fewer assertion lines) and
  `guard-tests.sh` (options that skip tests). Check: a changed assertion that keeps its line but checks less
  (`isEqualTo` to `isNotNull`, an exact value to a range), an expected value changed to match the new code, a test
  moved or renamed out of the run; a removed assertion is weakened even when other tests still pin the behaviour,
  because what is missing is the reason, not the coverage (whether a test still fails is GEN-01's).
- **GEN-09** retired: how a reviewer reports tests and coverage is `_common.md`'s ("Never report a check you could
  not run"; the test gate's artifact is the evidence for a test count or a coverage, #0-160), 2026-10-26. It said
  not to quote coverage, which the gate's artifact now gives.

## 2. Conventions

- **GEN-20 A non-obvious decision says why where it lives.** Javadoc or a comment explains it, with a backlog
  reference where one exists; a change that reverses an earlier decision says so where the old reasoning lived.
  Check: new or changed code whose reason is not visible from the code, and a reversed decision's old comment.
- **GEN-21 A `TODO` or `FIXME` names a backlog item** (`backlog #N`). Check: every new `TODO`/`FIXME`.
- **GEN-22 A test class is named for what it tests and lives in its package.** Under `src/test/java`, in the
  package of the code it tests: `<Class>Test`, or `<Class>IntegrationTest` for a test on a real database or broker
  (`AuthRepositoryIntegrationTest`, `DeadLetterPublisherKafkaIntegrationTest`); a test of one aspect may add it
  (`<Class>SecurityTest`), and Spring's generated `<Service>ApplicationTests` stays as it is. Helpers without tests
  of their own (`*TestSupport`, `*TestConfig`, a test application) carry no `Test` suffix, so the runner does not
  take them for tests. Check: a new test file's name and package.
- **GEN-23 A commit message is a Conventional Commit with a scope** (`fix(incident-service): …`,
  `docs(factory): …`), and names the backlog item when one applies (`backlog #N`, CLAUDE.md "Conventions"). Check:
  `git log --oneline <mergeBase>..HEAD`; a manual `/review` of uncommitted changes has no commit yet, so say so
  instead of a finding.
- **GEN-24 The change stays inside its item.** Unrelated edits (reformatting, drive-by refactors, a "while I was
  here" fix) are findings, even when correct. Check: each file and hunk of the diff against the item's acceptance
  criteria.

## 3. General correctness

- **GEN-30 The code does what its item and its own contract say.** No branch that cannot be reached, no inverted
  condition, no off-by-one, no null where one can occur. Check: each changed condition, bound and return value
  against the acceptance criteria and the method's Javadoc.
- **GEN-31 An error is handled, not lost.** No swallowed exception, no `catch` that drops the cause, no error mapped
  to the wrong HTTP status. Check: every new `catch`, rethrow and exception-to-status mapping.
- **GEN-32 Shared state has one writer at a time.** No check-then-act race, no missing guard where two writers
  exist. Check: a read followed by a write of the same row, cache or field, and what stops a second writer between
  them.

Do not restate what the compiler, the test gate and CI's checks already catch.

## Output notes

The test adequacy section always includes the behaviour → test map, even when every behaviour is
covered: one line per behaviour, `behaviour — Class#method` or `behaviour — no test`. For a change with
no behaviour (docs, config comments), say that instead of producing an empty map. For each finding:
**What** (file and line), **Why it matters** (what breaks, and for whom), **Suggested fix** (concrete,
not "consider improving this").

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
