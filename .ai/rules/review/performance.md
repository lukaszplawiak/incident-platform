# Review dimension: performance

Owned by the maintainer; agents never edit it. Moved from `.claude/agents/performance-reviewer.md` on
2026-10-05; the checks are unchanged, each now has an id.

Read by `review-performance` (the whole file, whenever it runs) and by the implementer: the rules the
architect's plan lists, or the whole file when the change reaches this area (self-check).

Read `.ai/context/architecture.md` first — virtual threads, the Kafka event flow and the shared Postgres
database are what this review depends on. Not every change needs this review: if the diff only touches
low-traffic paths (admin endpoints, one-off config, startup code), say so and return `APPROVE` rather
than manufacturing findings.

**Weight rule.** A finding is blocking only if the cost **grows with data or traffic** on a path that
sees it: N+1 on a per-request or per-record path, an unbounded query or collection, a missing index on a
hot query, a blocking call that can stall a partition. A constant cost of a few milliseconds, or any cost
on a path that runs rarely (a daily job over a bounded set, an admin endpoint), is non-blocking. `impact`
names the condition under which it bites (data volume, request rate, tenant count).

## 1. Database access patterns

- **PERF-01** N+1 queries: a loop that issues one query per iteration instead of a batched/joined fetch.
  Check new repository methods and any code iterating over a collection and calling a repository or
  `@Query` method per element.
- **PERF-02** Missing pagination on a query that could return an unbounded result set as the dataset grows
  (especially anything not obviously tenant- and time-scoped).
- **PERF-03** New queries without an index to support them — check migration files for a matching index
  when a new query filters or sorts on a column that previously had none (a partial index where the
  query has a fixed predicate).

## 2. Kafka consumers

- **PERF-10** Per-record work that does a blocking synchronous call (DB, HTTP) inside the consumer loop
  without regard for consumer lag — could this stall the partition under load? A consumer's
  `max.poll.records` × 5 s must fit in half its `max.poll.interval.ms` (checked at startup).
- **PERF-11** Batch size and commit strategy: does a change affect how many records are processed per
  poll, and is that intentional?
- **PERF-12** Anything that could turn a single bad record into a poison-pill loop instead of going to the
  dead-letter path.

## 3. Concurrency & virtual threads

- **PERF-20** Blocking calls wrapped correctly for virtual-thread execution — flag anything that looks
  like it could pin a carrier thread (synchronized blocks around blocking I/O being the classic case).
- **PERF-21** Async work: is `TenantAwareTaskDecorator`/the configured executor used instead of unbounded
  thread creation?
- **PERF-22** New `@Scheduled` jobs: reasonable frequency, a batch cap, and fast enough to finish before
  the next trigger, given ShedLock's lock duration (a processing budget validated against the lock, as
  the existing schedulers do).
- **PERF-23** A remote call (HTTP, Kafka send, email) inside a `@Transactional` method: it holds a
  connection and row locks for the duration of the call.

## 4. Memory & collections

- **PERF-30** Loading an entire table or Kafka topic's worth of data into memory where a streaming or
  paginated approach would do.
- **PERF-31** Caching that has no eviction/expiry, or that grows per-tenant without bound.

## 5. External calls

- **PERF-40** For postmortem-service's Gemini calls or any outbound HTTP: timeout configured, no
  retry-without-backoff that could amplify load during an outage.

## Output notes

List findings ordered by how much traffic/data volume the affected path sees (hot paths first). For each:
**What** (file and line), **When it bites** (the `impact`), **Suggested fix** (concrete, and
proportionate — don't recommend a cache or an index for a path that will never see meaningful volume).
If nothing on the changed paths is performance-sensitive, say so plainly instead of inventing marginal
findings.

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
