# Handoff: 0-196

Facts only. No reasoning about why the change is good: reviewers judge it themselves. Updated by the
implementer after every round.

## Changed
- `incident-service/src/test/java/com/incidentplatform/incident/api/IncidentDenyByDefaultTest.java`:
  - `@BeforeEach tenantsActive()` stubs `tenantStatusProvider.accessOf(anyString())` to `TenantAccess.FULL`.
  - new `@Test aValidUserTokenGetsPastAuthentication`: RESPONDER token from `JwtUtils.generateToken` (tenant
    `acme-corp`) on `GET /api/v1/incidents/{id}`; asserts status is not 401 and
    `verify(incidentQueryService).findById(id, "acme-corp")`.
  - `routes()`: a mapping with no method condition is walked with `EnumSet.allOf(RequestMethod.class)` (was
    `Set.of(RequestMethod.GET)`); Javadoc rewritten.
  - `EXEMPT`: the `/ws/**` entry and its comment removed; only `/error` is left. `isExempt()` unchanged.
  - `OpenRouteTestController`: new method-less `@RequestMapping(ANY_METHOD_PATH)`,
    `ANY_METHOD_PATH = "/api/v1/test-only/any-method"`; `OpenRoute` gets
    `theWalkSendsEveryMethodToAMethodlessMapping` (failure names `POST /api/v1/test-only/any-method [anonymous] -> 200`).
  - new `@Nested @NestedTestConfiguration(OVERRIDE) @WebMvcTest class WsRoute`: real `SecurityConfig`, no test chain,
    test-only `WsRouteTestController` at `/ws/test-only`; `theWalkReportsARestMappingUnderWs` (failure names
    `GET /ws/test-only [anonymous] -> 200`). Reuses `OpenRouteApplication`.
  - `enumerationFindsTheKnownRoutes` also asserts the outer walk contains neither
    `POST /api/v1/test-only/any-method` nor `GET /ws/test-only`.
  - Class Javadoc (positive control, a "No exemption for /ws/" section), collaborator and `TenantStatusProvider`
    comments, `OpenRoute` and `OpenRouteApplication` Javadoc updated.
- `README.md` "Infrastructure Hardening", the #0-191 bullet: incident's exempt set is `/error` only (the `/ws/**`
  clause removed); incident's test reports a REST mapping under `/ws/`; the closing sentence now covers incident's
  test (every method of a method-less mapping, positive control) and names ingestion's as #0-198.
- No production code, endpoint, topic, migration or config key changed.

## How to verify
- `./mvnw test -pl incident-service -am -Dtest=IncidentDenyByDefaultTest -Dsurefire.failIfNoSpecifiedTests=false`
  — 11 tests, 0 failures (run by the implementer).
- `./mvnw test -pl incident-service -am -Dsurefire.failIfNoSpecifiedTests=false` — incident-service 272 tests,
  0 failures, BUILD SUCCESS (run by the implementer). `-am` was needed because `shared` was not installed in the
  local repository.

## Tests changed and why
- `IncidentDenyByDefaultTest#routes` (used by every walk): walks every `RequestMethod` for a method-less mapping
  instead of `GET` alone — AC2.
- `IncidentDenyByDefaultTest#EXEMPT`: the `/ws/**` entry removed — AC3.
- `IncidentDenyByDefaultTest#enumerationFindsTheKnownRoutes`: assertions added (two more `doesNotContain` values),
  none removed; display name changed to "none of the test-only routes".

## Deliberately out of scope
- `IngestionDenyByDefaultTest` (same shape for ingestion): backlog #0-198.
- `ApiKeyAuthFilter` missing from incident's chain: backlog #0-193.
- The "until #0-196 they have no positive control" clauses of SEC-10 and SEC-12 in `.ai/rules/review/security.md`:
  a protected path; the owner's to update once #0-198 also lands.
- `SecurityConfig` of incident-service: unchanged.

## Noticed, not touched
- none

## Follow-up needed

## Disputed
- none
