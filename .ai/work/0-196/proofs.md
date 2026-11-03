# Proofs: 0-196

Each acceptance criterion and the evidence that shows it (`.ai/rules/acceptance.md`). No pasted logs and
no secrets: the full output is in `.ai/runs/0-196/` (gitignored), named here.

| Criterion | Evidence | Kind |
|---|---|---|
| AC1 | `IncidentDenyByDefaultTest#aValidUserTokenGetsPastAuthentication` asserts a RESPONDER token (tenant `FULL` via the `@BeforeEach` stub) gets a status other than 401 on `GET /api/v1/incidents/{id}`, and that `IncidentQueryService.findById(id, "acme-corp")` was called with the token's tenant | test |
| AC2 | `IncidentDenyByDefaultTest.OpenRoute#theWalkSendsEveryMethodToAMethodlessMapping` asserts the walk fails naming `POST /api/v1/test-only/any-method [anonymous] -> 200` (a method-less mapping in the `@NestedTestConfiguration(OVERRIDE)` context) | test |
| AC3 | `EXEMPT` in `IncidentDenyByDefaultTest` holds `/error` only; `isExempt` unchanged; `IncidentDenyByDefaultTest.WsRoute#theWalkReportsARestMappingUnderWs` fails if a `/ws/**` entry returns; `OpenRoute#theWalkFailsAndNamesTheOpenRoute` still green | test + reviewer |
| AC4 | `IncidentDenyByDefaultTest.WsRoute#theWalkReportsARestMappingUnderWs` asserts the walk, with the real `SecurityConfig` and no test chain, fails naming `GET /ws/test-only [anonymous] -> 200` | test |
| AC5 | `README.md` "Infrastructure Hardening", the #0-191 bullet: incident's exempt set `/error` only, `/ws/` reporting, positive control and every-method walk for incident's test | reviewer |
| AC6 | `./mvnw test -pl incident-service -am -Dsurefire.failIfNoSpecifiedTests=false` | command |

Test run: `./mvnw test -pl incident-service -am -Dsurefire.failIfNoSpecifiedTests=false` — PASS, incident-service
272 tests, 0 failures (seen by the implementer in its session output; not saved to `.ai/runs/0-196/`). The gate's own
run (`scripts/factory/run-tests.sh`) writes its log under `.ai/runs/0-196/`.
