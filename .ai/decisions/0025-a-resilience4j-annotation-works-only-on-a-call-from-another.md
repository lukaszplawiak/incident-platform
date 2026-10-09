# ADR-0025: A Resilience4j annotation works only on a call from another bean (backlog #0-21, #0-103)

- **Status:** Accepted
- **Backlog:** #0-21, #0-103
- **Source:** written by the owner in `.ai/context/project.md` ("Known Invariants and Limitations")
  on 2026-10-09, while the ADR split was in preparation; carried over verbatim. The text below is the
  original record, not rewritten into the Context / Decision / Consequences form.

## Record

`@Retry`, `@CircuitBreaker` and their fallbacks are Spring AOP: a method called on `this` runs
without them, silently. It happened twice in notification-service: `SlackWorkspaceClient`'s
breaker (#0-21, split into `CachingSlackWorkspaceClient` / `SlackWorkspaceClientImpl`) and the
Slack send's retry (#0-103, split into `SlackApiClient`). The fix both times was a bean of its own
for the remote call, not a `@Lazy` self-reference. A test built with `new` cannot see the
difference; `SlackWorkspaceClientResilienceTest` and `SlackApiClientResilienceTest` build a
minimal Spring context (AOP + Resilience4j auto-configuration, WireMock) and count the requests.
A new annotated client needs such a test, and a connect and read timeout, or a hung call never
throws and is never retried.
