# Proofs: 0-198

Each acceptance criterion and the evidence that shows it (`.ai/rules/acceptance.md`). No pasted logs and
no secrets: the full output is in `.ai/runs/<item>/` (gitignored), named here.

| Criterion | Evidence | Kind |
|---|---|---|
| AC1 | `IngestionDenyByDefaultTest#aValidUserTokenGetsPastAuthentication` asserts a valid RESPONDER token (tenant `FULL` through the mocked `TenantStatusProvider`) gets `isNotEqualTo(401).isEqualTo(403)` on `POST /api/v1/alerts/prometheus`, and `verifyNoInteractions(alertIngestionService)` | test |
| AC2 | `IngestionDenyByDefaultTest#everyRouteRefusesAnUnknownApiKey` verifies `lookup(eq(UNKNOWN_KEY), any())` `atLeastOnce()` after the `ApiKey ipl_…` walk and, after `clearInvocations`, after the `Bearer ipl_…` walk; by mutation (removing `addFilterBefore(apiKeyAuthFilter, JwtAuthFilter.class)`, reverted) it failed at the first verify, line 197 | test + mutation |
| AC3 | `IngestionDenyByDefaultTest#routes` reads `EXEMPT` through `isExempt` (verbatim from `IncidentDenyByDefaultTest`); `#everyRouteRefusesAnAnonymousCaller` still green, `/error` still exempt | test |
| AC4 | `IngestionDenyByDefaultTest.OpenRoute#theWalkSendsEveryMethodToAMethodlessMapping` expects an `AssertionError` naming `POST /api/v1/test-only/any-method [anonymous] -> 200`; `#enumerationFindsTheKnownRoutes` asserts that route is not in the outer context | test |
| AC5 | README.md "Infrastructure Hardening", entry "Every route refuses an unauthenticated caller": ingestion's test has a positive control and shows its key lookup was asked, in both forms (#0-198) | reviewer-checked |
| AC6 | `./mvnw test -pl ingestion-service -am -Dsurefire.failIfNoSpecifiedTests=false` — BUILD SUCCESS, ingestion-service 209 tests, 0 failures | command |

Test run: `./mvnw test -pl ingestion-service -am -Dsurefire.failIfNoSpecifiedTests=false` — PASS, 209 ingestion-service
tests, seen in this session's console only (this session could not redirect output to a file); the autopilot gate's
own log under `.ai/runs/0-198/` is the record.
