# Review dimension: performance

Owned by the maintainer; agents never edit it. Moved from `.claude/agents/performance-reviewer.md` on
2026-10-05, each check with an id; rewritten as invariants on 2026-10-25 (backlog #0-115): each rule says what
is true of the code, and its "Check:" line what to look for in a diff. The ids did not change.

Read by `review-performance` (the whole file, whenever it runs) and by the implementer: the rules the
architect's plan lists, or the whole file when the change reaches this area (self-check). For the implementer
a rule is what the code must be; for the reviewer, its "Check:" line is where a violation shows.

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

- **PERF-01 One query for a collection, not one per element.** Code that goes over a collection fetches what
  it needs in one batched or joined query (`findAllById`, an `IN` predicate, a fetch join), never with a
  repository call, a lazy association or a client call per element. Check: a new loop, stream or `forEach`
  over entities or ids whose body reaches a repository, a lazy getter or an HTTP client.
- **PERF-02 A query that grows with the data is bounded.** It is paged, limited, or scoped to a tenant and a
  time window. Check: a new repository method returning a `List` or `Stream` without a `Pageable`, a limit or
  such a predicate, on a table that grows (incidents, alerts, audit events, the outbox).
- **PERF-03 A new filter or sort has an index.** A query that filters or sorts on a column has an index that
  serves it, a partial one when its predicate is fixed. Check: a new `WHERE` or `ORDER BY` column against the
  indexes in that service's migrations.

## 2. Kafka consumers

- **PERF-10 A consumer keeps up with its partition.** A whole poll's work finishes inside
  `max.poll.interval.ms`: the dead-letter copies of a whole poll (`max.poll.records` × the 5 s dead-letter
  timeout) fit in half of it, which each consuming service's `KafkaConfig` checks at startup
  (`DeadLetterPublisher.requireFitsPollInterval`), and the per-record blocking work (a query, an HTTP call) fits in
  the rest. Check: a new blocking call per record, or a change to either setting.
- **PERF-11 How many records a poll takes, and when they commit, changes only on purpose.** Check: a change to
  `max.poll.records`, a batch listener or the ack mode, without a reason in the PR or a Javadoc.
- **PERF-12 A bad record leaves the partition once.** A record that keeps failing goes to the dead-letter
  topic (`DeadLetterPublisher.deadLetterThenAcknowledge`) after a bounded retry, so it never loops. Check: a new
  catch, rethrow or retry in a listener, and whether every way out ends in an acknowledge or a dead letter.

## 3. Concurrency & virtual threads

- **PERF-20 No blocking I/O while a carrier thread is pinned.** On Java 21 a virtual thread inside a
  `synchronized` block or method pins its carrier, so no database, HTTP, Kafka or file call runs inside one
  (`ReentrantLock` instead). Check: a new or changed `synchronized` whose body reaches I/O.
- **PERF-21 Async work runs on a bounded executor.** The configured executor (with `TenantAwareTaskDecorator`),
  never a thread per task. Check: `new Thread`, `Executors.new…`, or a `CompletableFuture.*Async` without the
  executor. (The tenant context is SEC-05's.)
- **PERF-22 A scheduled job finishes inside its lock.** It takes a capped batch and stops at a processing
  budget validated against its ShedLock `lockAtMostFor`, as the existing schedulers do (`AuthEmailScheduler`).
  Check: a new `@Scheduled` without a batch cap, a budget or the validation, or a frequency shorter than a run;
  a job that is one bounded statement (a purge by date, `AuthEmailScheduler`'s) needs no cap or budget.
- **PERF-23 No remote call inside a transaction.** An HTTP call, a Kafka send or an email runs outside
  `@Transactional`, where it would hold a connection and row locks for its duration; an event on
  `incidents.lifecycle` or `audit.events` goes through the outbox (the other topics are sent directly). Check: a
  new remote call reachable from a `@Transactional` method.

## 4. Memory & collections

- **PERF-30 Nothing loads a whole table or topic into memory.** Data that grows is streamed or paged. Check:
  a new `findAll()`, a collect over an unbounded stream, a topic read from its beginning into a collection.
- **PERF-31 A cache is bounded.** A size limit or an expiry bounds it, and a per-tenant key cannot make it grow
  without bound. Check: a new cache with neither `maximumSize` nor `expireAfter…`, or keyed by a value that grows.

## 5. External calls

- **PERF-40 An outbound call has a timeout and a bounded retry.** A connect and a read timeout, and retries with
  backoff and a deadline, so an outage of the other side is not amplified (postmortem-service's Gemini calls
  included). Check: a new client or call without timeouts, a retry loop without backoff or deadline.

## Output notes

List findings ordered by how much traffic/data volume the affected path sees (hot paths first). For each:
**What** (file and line), **When it bites** (the `impact`), **Suggested fix** (concrete, and
proportionate — don't recommend a cache or an index for a path that will never see meaningful volume).
If nothing on the changed paths is performance-sensitive, say so plainly instead of inventing marginal
findings.

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
