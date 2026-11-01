# ADR-0048: A service's whole API is checked by one route-walking deny-by-default test, a copy per service

- **Status:** Proposed
- **Backlog:** #0-191 (incident-service, ingestion-service); #0-192 copies it (notification, oncall, postmortem)
- **Date:** 2026-11-01
- **Author:** architect agent (autopilot run 20261101-154451)
- **Reversible:** yes (test code only; no production class, contract or dependency changes)

## Context

Only auth-service checks all of its routes at once (`ApiKeyDenyByDefaultTest`, `IntrospectionTokenDenyByDefaultTest`:
every `RequestMappingHandlerMapping` entry through the real `SecurityConfig`). The five other services with an API
fork the `SecurityFilterChain` (SEC-12) and test listed endpoints only (`IncidentControllerSecurityTest`,
`AlertIngestionControllerSecurityTest`, ...), so a new controller, a `permitAll()` in a chain, a profile chain like
incident's `DevSecurityConfig` that leaks, or a `PUBLIC_PATHS` entry matching a real route is caught by review alone.
The two auth-service tests exempt routes by reading `SharedSecurityAutoConfiguration.PUBLIC_PATHS`, which would
exempt exactly the route a bad new entry opens.

## Decision

Each service with an API has `<Service>DenyByDefaultTest` in its `api` test package, of this shape:

1. **Context**: `@WebMvcTest` with a nested `@SpringBootApplication` test application (`@ContextConfiguration`, as
   `IncidentControllerSecurityTest` does: the service's own application class has a plain `@ComponentScan` that
   bypasses the slice filter) whose scan starts at the **service's root package**, so a controller in a new package is
   enumerated too; the real `SecurityConfig`, `UnauthorizedEntryPoint`, a real `JwtUtils` with its properties, and
   `@ImportAutoConfiguration(SharedSecurityAutoConfiguration.class)` (as `AlertIngestionControllerSecurityTest`), so
   the chain's filters are the beans production builds. Services and lookups behind them are `@MockitoBean`s; an
   `ApiKeyLookupService` is mocked only where the service has a real one (ingestion: `Invalid`). No profile is active.
2. **Walk**: every pattern and method of `RequestMappingHandlerMapping` (no method = GET), path variables filled
   with a UUID, JSON body `{}`, for three callers: no `Authorization` → 401; a purpose token from
   `JwtUtils.generatePurposeToken` addressed to the service under test → 401 or 403; an unknown key as
   `ApiKey ipl_…` and as `Bearer ipl_…` → 401. Every miss is collected as `<METHOD> <pattern> [<caller>] -> <status>`
   and asserted empty, so the failure names each route.
3. **Exempt set**: a literal `Map<String, String>` in the test, pattern to reason. It never reads `PUBLIC_PATHS`.
   An entry exists only for a route the chain permits on purpose (e.g. Spring Boot's `/error`, which renders the
   error of a request the chain already handled; incident `/ws/**`, STOMP authenticates the `CONNECT` frame).
4. **Enumeration guard**: the checked routes are at least the count on the branch that adds the test (a lower bound,
   so a route added later passes without anyone editing the test; one removed lowers it on purpose) and contain named
   known routes of each controller.
5. **Negative case**: a `@Nested` class with `@NestedTestConfiguration(OVERRIDE)` whose context has the same
   `SecurityConfig` plus a test-only controller (`/api/v1/test-only/open`) and a test-only chain at highest precedence
   that permits it (the shape of `DevSecurityConfig`); the same walk method must fail on it and name the route. The
   outer context must not contain the test-only route (asserted).
6. **One copy per service**: the walk is a private method of each test. A shared helper would need a test-jar of
   `shared`, a new inter-module dependency (`NEEDS_HUMAN`, `_common.md`); it can follow once all five exist.

## Alternatives considered

- **Exempt by `PUBLIC_PATHS`, as the auth-service tests do**: a new `PUBLIC_PATHS` entry matching a controller route
  would exempt the route it opens; the item's point is to catch that.
- **Exact route count**: fails on every new route, so the test is edited with each controller and stops checking
  "without anyone adding it". A lower bound still fails an enumeration that finds nothing.
- **Scan only the packages the precedent lists (`incident.api`, `incident.config`)**: a controller added in another
  package of the service would be outside the walk.
- **Negative case by mutating the real chain** (a flag or property in `SecurityConfig`): production code changed for a
  test, and a switch that opens routes in the shipped chain.
- **`@SpringBootTest` with the full application**: needs Kafka, Postgres, Redis and WebSocket infrastructure for a
  question the web slice answers.

## Consequences

- A route a service adds is refused to an anonymous caller, a purpose token and an unknown key, or the build fails
  naming it. Whether its *role* is right stays SEC-10's review.
- A new exempt entry is a reviewed literal with its reason; a reviewer treats an entry without one, or a reference to
  `PUBLIC_PATHS`, as a violation (SEC-10).
- #0-192 copies this shape for notification, oncall and postmortem; incident's and the other forks' lack of
  `ApiKeyAuthFilter` is #0-193 (the tests pin 401 either way). escalation-service has no controller and no test until
  it has one.
