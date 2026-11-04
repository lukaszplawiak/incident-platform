# Handoff: 0-198

Facts only. No reasoning about why the change is good: reviewers judge it themselves. Updated by the
implementer after every round.

## Changed
- `ingestion-service/src/test/java/com/incidentplatform/ingestion/api/IngestionDenyByDefaultTest.java`:
  - `@BeforeEach` renamed `everyKeyIsUnknown` → `everyKeyIsUnknownAndTenantsActive`; it also stubs
    `tenantStatusProvider.accessOf(anyString())` → `TenantAccess.FULL`. The `@MockitoBean` comment on
    `tenantStatusProvider` no longer says "never asked".
  - New `aValidUserTokenGetsPastAuthentication`: a `JwtUtils.generateToken` RESPONDER token for `acme-corp` on
    `POST /api/v1/alerts/prometheus` (JSON `{}`) gets `isNotEqualTo(401).isEqualTo(403)`, and
    `verifyNoInteractions(alertIngestionService)`.
  - `everyRouteRefusesAnUnknownApiKey`: `verify(apiKeyLookupService, atLeastOnce()).lookup(eq(UNKNOWN_KEY), any())`
    after the `ApiKey ipl_…` walk, `clearInvocations(apiKeyLookupService)`, and the same verify after the
    `Bearer ipl_…` walk.
  - `routes()`: `isExempt(pattern)` (copied verbatim from `IncidentDenyByDefaultTest`) in place of
    `EXEMPT.containsKey(pattern)`; `EnumSet.allOf(RequestMethod.class)` in place of `Set.of(RequestMethod.GET)` for a
    mapping with no method condition. `routes()` and `EXEMPT` Javadoc rewritten as incident's, citing #0-198.
  - `OpenRouteTestController`: new `ANY_METHOD_PATH = "/api/v1/test-only/any-method"` with a methodless
    `@RequestMapping` handler. New `OpenRoute#theWalkSendsEveryMethodToAMethodlessMapping`.
  - `enumerationFindsTheKnownRoutes`: also `doesNotContain("POST " + ANY_METHOD_PATH)`; Javadoc says every ingestion
    mapping declares its method, so the bound of 2 stays; display name says "none of the test-only routes".
  - Class Javadoc: the positive control (ADR-0048 rule 7), and a new section on why the lookup verify proves
    `ApiKeyAuthFilter` is in the chain and not only its plain servlet registration (backlog #0-193).
- `README.md` "Infrastructure Hardening", entry "Every route refuses an unauthenticated caller": ingestion's test
  (#0-198) listed with incident's as walking every method of a methodless mapping, with the same `/**` exempt matching
  and a positive control; its unknown-key walk shows the mocked key lookup was asked for the key in both forms.
- No production code, no `shared`, no config.

## How to verify
- `./mvnw test -pl ingestion-service -am -Dtest=IngestionDenyByDefaultTest -Dsurefire.failIfNoSpecifiedTests=false`
  — 9 tests, 0 failures (seen in this session). `-am` is needed here because `shared` is not installed in the local
  repository of this environment.
- `./mvnw test -pl ingestion-service -am -Dsurefire.failIfNoSpecifiedTests=false` — BUILD SUCCESS; ingestion-service
  209 tests, 0 failures (seen in this session).
- Mutation (scratch edit, reverted, never committed): with `.addFilterBefore(apiKeyAuthFilter, JwtAuthFilter.class)`
  removed from ingestion's `SecurityConfig`, `everyRouteRefusesAnUnknownApiKey` failed at the first verify
  (`IngestionDenyByDefaultTest.java:197`, "Wanted but not invoked … zero interactions with this mock"), while every
  route still answered 401. `git status` after the revert shows only the test file modified.

## Tests changed and why
- `IngestionDenyByDefaultTest#everyRouteRefusesAnUnknownApiKey`: assertions added (AC2), none removed.
- `IngestionDenyByDefaultTest#enumerationFindsTheKnownRoutes`: one `doesNotContain` value added (AC4), none removed;
  display name changed to plural.
- `@BeforeEach` renamed and given a second stub (AC1); the existing lookup stub is unchanged.

## Deliberately out of scope
- `.ai/rules/review/security.md` SEC-10 / SEC-12 "until #0-196 … ingestion's does not show its key lookup was asked"
  clauses: owner-owned rule file (`protected-paths.md`), for the owner after merge.
- ingestion's `SecurityConfig` and `ApiKeyAuthFilter`'s plain servlet registration: backlog #0-193.

## Noticed, not touched
- `.ai/rules/review/security.md` SEC-10/SEC-12 text still says incident's and ingestion's walks have no positive
  control and that ingestion's does not show its key lookup was asked; both are true no longer once this item and
  #0-196 merge — proposed backlog item: owner updates the two "until #0-196" clauses.

## Follow-up needed

## Disputed
