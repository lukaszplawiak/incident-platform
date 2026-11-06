# Handoff: #0-197

Facts only. No reasoning about why the change is good: reviewers judge it themselves. Updated by the
implementer after every round.

## Changed
- `auth-service/src/test/java/com/incidentplatform/auth/api/ApiKeyDenyByDefaultTest.java`:
  `PUBLIC_POSTS`, the `SharedSecurityAutoConfiguration.PUBLIC_PATHS` loop, every `startsWith` and the
  `/api/v1/teams` prefix removed. New literal `EXEMPT` (`Map.ofEntries`, `/error` plus ten `POST <path>` keys, a
  reason each) and `KEY_ROUTES` (the six exact `TeamController` patterns, a reason each naming SecurityConfig's
  teams:read/teams:write rule). New private static helpers `routes`, `allRoutes` (every `RequestMethod` for a mapping
  with no method condition), `assertEveryRoute` (collects `METHOD pattern -> status`; 403 expected, `TRACE` 400),
  `isExempt(Map, RequestMethod, String)` (`METHOD pattern` or bare key; a `/**` key matched with
  `PathPatternParser.defaultInstance`), `isValidKey`, `expectedStatus`. New tests `everyExemptEntryHasAReason`,
  `exemptEntriesNameRealRoutes`, `exemptMatchingFollowsRule3`; new `@Nested OpenRoute`
  (`@NestedTestConfiguration(OVERRIDE)`, `@WebMvcTest`, beans listed in `@ContextConfiguration`, no
  `@SpringBootConfiguration`) with `theWalkFailsAndNamesTheOpenRoute` and
  `theWalkSendsEveryMethodToAMethodlessMapping`; nested `OpenRouteTestController` (`@RestController`) and
  `OpenRouteChain` (no stereotype, `@Bean @Order(HIGHEST_PRECEDENCE)` chain on `/api/v1/test-only/**`, permitAll).
  Class Javadoc: why the exempt set is a literal, `Fixed (backlog #0-197)`.
- `auth-service/src/test/java/com/incidentplatform/auth/api/IntrospectionTokenDenyByDefaultTest.java`: the same
  `EXEMPT` plus `POST /api/v1/internal/api-keys/introspect`, the same helpers (private to this class, ADR-0048 rule
  6), the same three entry tests, and `theWalkSendsEveryMethodToAMethodlessMapping` (a `RequestMappingHandlerMapping`
  of its own with one method-less mapping registered; `routes()` gives all 8 methods). Class Javadoc updated the same
  way.
- `README.md` "Infrastructure Hardening", #0-191 entry: the auth-service sentence now describes both walks' literal
  exempt set, the exact team patterns and the negative case (backlog #0-197).
- `.ai/decisions/0048-every-route-is-walked-deny-by-default.md`: Context and "Alternatives considered" now say the
  auth-service tests read `PUBLIC_PATHS` until #0-197 (AC6). Status and rules unchanged.
- No production code, endpoint, topic, migration or config key changed.

## How to verify
- `./mvnw test -pl auth-service -am -Dtest=ApiKeyDenyByDefaultTest,IntrospectionTokenDenyByDefaultTest -Dsurefire.failIfNoSpecifiedTests=false`
  — 22 tests, all pass (seen in this round).
- `./mvnw test -pl auth-service -am` — 1010 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS (seen in this
  round; AC7).

## Tests changed and why
- `ApiKeyDenyByDefaultTest#everyOtherRouteIsForbidden`: rewritten onto `routes`/`assertEveryRoute` (AC1: literal
  exempt set, no `PUBLIC_PATHS`/`startsWith`; AC2: `KEY_ROUTES` in place of the teams prefix; AC3: every method for a
  method-less mapping). The 403 expectation, the `hasSizeGreaterThan(30)` lower bound and the four named routes are
  kept; `doesNotContain` of the two test-only routes is added (AC4). Only `TRACE` is expected at 400, and only a
  mapping with no method condition is sent it (StrictHttpFirewall, ADR-0049 rule 4).
- `IntrospectionTokenDenyByDefaultTest#everyOtherRouteIsForbidden`: rewritten the same way (AC1, AC3); 403
  expectation and `hasSizeGreaterThan(30)` kept, `doesNotContain("POST /api/v1/internal/api-keys/introspect")` added;
  `TRACE` 400 as above.
- No other existing test changed (`teamsReadScope`, `headIsARead`, `writeScopeDoesNotRead`,
  `teamsWriteScopeKeepsRoleCheck`, `pathVariantsStayClosed`, `noScopeNoTeams`, `jwtUnaffected` and the introspection
  route tests are as they were).

## Deliberately out of scope
- Merging the two walks into one test, or a shared helper: the owner decided they stay two tests; ADR-0048 rule 6.

## Noticed, not touched
- The outer contexts of both tests scan only `auth.api` and `auth.config` (and `shared.*`) through
  `AuthApiTestApplication` (`auth-service/src/test/java/com/incidentplatform/auth/api/AuthApiTestApplication.java:26`),
  not the service's root package as ADR-0048 rule 1 asks — a controller in another `auth.*` package would not be
  walked; the owner decided to fix the walks in place. Proposed backlog item: walk auth-service's controllers from
  the service root package without breaking `AuthApiTestApplication` discovery.
- `.ai/rules/review/security.md` SEC-14 still says the exempt routes "come from `PUBLIC_PATHS` until #0-197"; a
  protected path, for the owner to update after merge.
- `OpenRouteTestController` is a nested `@RestController`: `AuthServiceApplication`'s plain `@ComponentScan` adds it
  to `@SpringBootTest` contexts (`AuthRepositoryIntegrationTest`), as it already does
  `PlatformTenantControllerSecurityTest.UnannotatedPlatformController`; there SecurityConfig's `anyRequest()` rule
  covers it. Proposed backlog item: a `TypeExcludeFilter` on `AuthServiceApplication`'s scan (production class).

## Follow-up needed

## Disputed
