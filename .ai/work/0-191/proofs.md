# Proofs: 0-191

Each acceptance criterion and the evidence that shows it (`.ai/rules/acceptance.md`). No pasted logs and
no secrets: the full output is in `.ai/runs/<item>/` (gitignored), named here.

| Criterion | Evidence | Kind |
|---|---|---|
| AC1 | `IncidentDenyByDefaultTest#everyRouteRefusesAnAnonymousCaller` (401), `#everyRouteRefusesAPurposeToken` (real `JwtUtils.generatePurposeToken(..., ServiceNames.INCIDENT_SERVICE)`, 401 or 403), `#everyRouteRefusesAnUnknownApiKey` (`ApiKey ipl_…` and `Bearer ipl_…`, 401, chain without `ApiKeyAuthFilter`); `IngestionDenyByDefaultTest`, the same three (lookup mocked `Invalid`, real `ApiKeyAuthFilter`). Both through the real `SecurityConfig` and the auto-configuration's filters | test |
| AC2 | `IncidentDenyByDefaultTest.OpenRoute#theWalkFailsAndNamesTheOpenRoute`, `IngestionDenyByDefaultTest.OpenRoute#theWalkFailsAndNamesTheOpenRoute`: the same `assertEveryRoute` throws `AssertionError` on a context with `GET /api/v1/test-only/open` and a `permitAll` chain | test |
| AC3 | the same two negative cases assert the message contains `GET /api/v1/test-only/open [anonymous] -> 200` (method, pattern, caller, status); every walk collects misses as `METHOD pattern [caller] -> status` and asserts the list empty | test |
| AC4 | `IncidentDenyByDefaultTest#enumerationFindsTheKnownRoutes` (≥ 8; `GET /api/v1/incidents/{id}`, `GET /api/v1/incidents/{incidentId}/audit`; not the test-only route); `IngestionDenyByDefaultTest#enumerationFindsTheKnownRoutes` (≥ 2; `POST /api/v1/alerts/{source}`, `GET /api/v1/alerts/sources`; not the test-only route) | test |
| AC5 | `IncidentDenyByDefaultTest#noProfileAndNoDevChain`: no active profile, no `DevSecurityConfig` bean although the class is imported, no `/dev` route | test |
| AC6 | `EXEMPT` is a literal `Map.of(pattern, reason)` in each test; neither test reads `SharedSecurityAutoConfiguration.PUBLIC_PATHS` (named only in the class Javadoc that says why) | reviewer-checked |
| AC7 | README "Infrastructure Hardening" > "Application", bullet "Every route refuses an unauthenticated caller (backlog #0-191, ADR-0048)" naming both tests | reviewer-checked |

Test run (implementer, r1): `./mvnw test -pl incident-service,ingestion-service -am` — BUILD SUCCESS, incident-service
267 tests, 0 failures; shared and ingestion-service green. The session could not write under `.ai/runs/`, so the full
log of record is the one `scripts/factory/run-tests.sh` writes to `.ai/runs/0-191/` for this round.
