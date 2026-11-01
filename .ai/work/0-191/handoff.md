# Handoff: 0-191

Facts only. No reasoning about why the change is good: reviewers judge it themselves. Updated by the
implementer after every round.

## Changed
- `incident-service/src/test/java/com/incidentplatform/incident/api/IncidentDenyByDefaultTest.java` (new): `@WebMvcTest`
  with a nested `TestApplication` scanning `com.incidentplatform.incident` (+ `shared.security`, `shared.exception`,
  `shared.observability`), `@Import` of `SecurityConfig`, `DevSecurityConfig`, `UnauthorizedEntryPoint`, `JwtUtils`,
  `@ImportAutoConfiguration(SharedSecurityAutoConfiguration.class)`. No `JwtAuthFilter` bean defined, `JwtUtils` not
  mocked. Mocked: `IncidentQueryService`, `IncidentCommandService`, `AuditQueryService`, `ServiceTokenProvider`,
  `TenantStatusProvider`. Tests: `everyRouteRefusesAnAnonymousCaller`, `everyRouteRefusesAPurposeToken`,
  `everyRouteRefusesAnUnknownApiKey`, `enumerationFindsTheKnownRoutes`, `noProfileAndNoDevChain`,
  `OpenRoute#theWalkFailsAndNamesTheOpenRoute` (`@Nested`, `@NestedTestConfiguration(OVERRIDE)`, its own context with
  `OpenRouteTestController` `GET /api/v1/test-only/open` and `OpenRouteChainTestConfig`, a `@Order(HIGHEST_PRECEDENCE)`
  `permitAll` chain on `/api/v1/test-only/**`).
- `ingestion-service/src/test/java/com/incidentplatform/ingestion/api/IngestionDenyByDefaultTest.java` (new): same shape;
  scan `com.incidentplatform.ingestion` (+ `shared.security`, `shared.exception`); `SecurityConfig` (its own
  `JwtAuthFilter`), `UnauthorizedEntryPoint`, `JwtUtils`, the auto-configuration. Mocked: `AlertIngestionService`,
  `RateLimitingService`, `ClientIpResolver`, `ServiceTokenProvider`, `TenantStatusProvider`, and
  `ApiKeyAuthFilter.ApiKeyLookupService` (answers `Invalid` for every key); `ApiKeyAuthFilter` itself is the
  auto-configuration's real bean. No `ingestion` profile check (no profile chain there). Tests: the three walks,
  `enumerationFindsTheKnownRoutes`, `OpenRoute#theWalkFailsAndNamesTheOpenRoute`.
- `README.md` "Infrastructure Hardening" > "Application": new bullet "Every route refuses an unauthenticated caller
  (backlog #0-191, ADR-0048)" after "Service identity".
- No production code, config key, endpoint, topic or migration changed.

Confirmed on this branch:
- incident: 8 routes walked (7 `IncidentController`, 1 `IncidentAuditController`); lower bound 8. Exempt map: `/error`
  (found by the walk: `BasicErrorController`) and `/ws/**` (not a `RequestMappingHandlerMapping` route; kept per plan
  step 3, its comment says so). `DevTokenController` is not in the context. No springdoc route is in the slice.
- ingestion: 2 routes walked (`POST /api/v1/alerts/{source}`, `GET /api/v1/alerts/sources`); lower bound 2. Exempt
  map: `/error`. `PayloadSizeLimitFilter` is not in the slice: it is registered by `PayloadSizeLimitFilterConfig`, a
  `@Configuration` the `@WebMvcTest` scan leaves out, and the test does not import it.
- incident's unknown-key 401 comes from `UnauthorizedEntryPoint` (log: "Unauthenticated request to: …"); ingestion's
  from `ApiKeyAuthFilter` (log: "Invalid or revoked API key …").

## How to verify
- `./mvnw test -pl incident-service -am -Dtest=IncidentDenyByDefaultTest -Dsurefire.failIfNoSpecifiedTests=false` —
  6 tests (5 + the nested negative case).
- `./mvnw test -pl ingestion-service -am -Dtest=IngestionDenyByDefaultTest -Dsurefire.failIfNoSpecifiedTests=false` —
  5 tests (4 + the nested negative case).
- `git grep -n PUBLIC_PATHS -- '*DenyByDefaultTest.java'` lists only the two auth-service tests' lines and the two
  new tests' Javadoc sentence that names it (no code reference).

## Tests changed and why
- none.

## Deliberately out of scope
- notification-service, oncall-service, postmortem-service: backlog #0-192.
- `ApiKeyAuthFilter` in incident's chain: backlog #0-193.
- SEC-10 / SEC-12 "Backed by" lines in `.ai/rules/review/security.md`: the owner's after-merge step (Owner-only path).

## Noticed, not touched
- `shared/src/main/java/com/incidentplatform/shared/security/JwtAuthFilter.java:26-32` ("Why no @Component") says that
  declaring the filter as a `@Bean` gives exactly one registration; Spring Boot also registers every `Filter` bean as a
  plain servlet filter (seen in the negative case: `JwtAuthFilter` logs "No JWT token in request to:
  /api/v1/test-only/open" although the test chain does not contain it), and `OncePerRequestFilter` is what keeps it to
  one run. The same applies to the auto-configuration's `ApiKeyAuthFilter` bean in services whose chain does not add
  it (incident): it runs as a servlet filter after the security chain. — proposed backlog item: correct the Javadoc
  and decide whether the auth filters' servlet registrations should be disabled (`FilterRegistrationBean.setEnabled(false)`),
  relevant to #0-193.

## Follow-up needed

## Disputed
