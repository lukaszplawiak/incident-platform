# Review dimension: architecture

Owned by the maintainer; agents never edit it. Sections 2 and 3 were moved from
`.claude/agents/code-reviewer.md` on 2026-10-05 (unchanged, now with ids); section 1 and 4 are new. The
`architect` agent reads this file too, before it plans an item.

Read by `review-architecture` (the whole file, for every change) and by the implementer, which reads this
file in full for every item; the architect's plan only adds emphasis to the rules at risk.

Read `.ai/context/architecture.md`, the "Service Map" in `.ai/context/project.md`, and the ADRs whose area
the change touches (`.ai/decisions/README.md`). Judge the change as a part of the whole system: a change
can be locally correct and still make the system worse — a second mechanism for a solved problem, a
responsibility in the wrong service, a coupling nobody decided on.

## 1. Boundaries and responsibilities

- **ARC-01** Does each new responsibility live in the service the Service Map gives it? A responsibility
  in another service needs an ADR (`NEEDS_HUMAN` if none exists).
- **ARC-02** One mechanism per problem: before accepting a new retry helper, mapper, HTTP client wrapper,
  cache, scheduler pattern or error-handling style, search for the existing one. A second mechanism for a
  solved problem is a finding; name the existing one.
- **ARC-03** `shared` is platform-wide policy, not a utility bag: domain code of one service does not move
  into `shared`, and a change to `shared` is justified for all 7 services.
- **ARC-04** Communication between services: an event through the outbox, or the narrow HTTP pull with a
  service token (ADR-0003). Reading another service's tables, replicating auth-service-owned data over
  Kafka (#0-30), or a new synchronous dependency in a hot path needs an ADR.
- **ARC-05** The change respects every ADR it touches; a change that reverses one comes with a new ADR
  (`Supersedes ADR-NNNN`), otherwise `NEEDS_HUMAN`.
- **ARC-06** Before proposing an alternative already rejected in README "Design Decisions" (Spring State
  Machine, Kafka Streams, full CQRS, RS256/Keycloak): it was rejected on purpose — `NEEDS_HUMAN`.

## 2. Shared security configuration (structure)

- **ARC-10** Does any new or touched service declare its own `SecurityFilterChain`? If so, does it
  silently take over CORS and `PUBLIC_PATHS` instead of extending the shared chain's contract? This is a
  recurring bug class (see commits d80541f, ec4eae4) — flag it even if the tests pass. (Whether it widens
  access is `security`'s SEC-12; here the question is whether forking was needed at all.)

## 3. Already-decided patterns

- **ARC-20** If this change touches something that looks like it needed retrying, scheduling, or
  double-processing protection, confirm it actually uses the established pattern rather than a new
  one-off mechanism:
  - transactional outbox for `incidents.lifecycle` and for audit events (no direct send; an audit call
    followed by a throw in the same transaction loses its event — audit a refusal after the rollback);
  - ShedLock on every `@Scheduled` job;
  - a Kafka listener is `MANUAL_IMMEDIATE`, never `@Transactional`, acknowledges only once processed or
    dead-lettered (`DeadLetterPublisher.deadLetterThenAcknowledge`) and `nack`s a transient failure
    (`KafkaFailures`); a `return` without acknowledging is no retry;
  - `@Version` optimistic locking on mutable entities, with a `Long` version field left uninitialised;
  - a bulk `@Modifying(clearAutomatically = true)` also sets `flushAutomatically = true`;
  - idempotency checks before any outbound notification (ADR-0017);
  - retries of an outbound send bounded by a deadline, not an attempt count (`AuthEmailRetryPolicy`);
  - an outbox row has one writer after its INSERT; state-guarded conditional UPDATEs where documented.
- **ARC-21** Lock order and lock waits where tenant status is involved (tenant row, then token rows;
  `findByIdForUpdate` / `findStatusForSignIn`), per `.ai/context/architecture.md`.

## 4. Enforced by tests — do not review by hand

Rules a test or CI script already enforces. Do not spend a finding on them; if CI is red on one, the
autopilot already stopped before you were called.

- No Spring profile configuration or literal secret under `src/main/resources`
  (`.github/scripts/check-packaged-profiles.sh`, #0-81).
- Every service but auth-service sets `auth-service.base-url` from `${AUTH_SERVICE_URL}`
  (`check-tenant-status-config.sh`, #0-82).
- Every committed `spring.datasource.password` is exactly `${DB_PASSWORD}` (`check-db-password-config.rb`,
  #0-66).
- Every Dockerfile has a Deployment in `k8s/base`; staging/prod overlays set no Spring profile (CI).
- ArchUnit rules: none yet (backlog #0-108). Each rule added there is listed here.

## Output notes

For each finding: **What** (file and line), **Why it matters** (which part of the system gets worse, and
how a later change will trip over it), **Suggested fix** (the existing mechanism to use, or the ADR that
is needed).

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
