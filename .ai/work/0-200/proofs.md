# Proofs: 0-200

Each acceptance criterion and the evidence that shows it (`.ai/rules/acceptance.md`). No pasted logs and
no secrets: the full output is in `.ai/runs/<item>/` (gitignored), named here.

| Criterion | Evidence | Kind |
|---|---|---|
| AC1 | `IncidentDenyByDefaultTest.CorsPreflight#allowedPreflightIs200WithAllowOrigin` (`OPTIONS /api/v1/incidents`), `IngestionDenyByDefaultTest.CorsPreflight#allowedPreflightIs200WithAllowOrigin` (`OPTIONS /api/v1/alerts/prometheus`), `NotificationDenyByDefaultTest.CorsPreflight#allowedPreflightIs200WithAllowOrigin` (`OPTIONS /api/v1/test-only/unmapped`, the `anyRequest()` rule), `OncallDenyByDefaultTest.CorsPreflight#allowedPreflightIs200WithAllowOrigin` (`OPTIONS /api/v1/oncall/schedules`), `PostmortemDenyByDefaultTest.CorsPreflight#allowedPreflightIs200WithAllowOrigin` (`OPTIONS /api/v1/postmortems`): `Origin: http://localhost:4200` and `Access-Control-Request-Method`, no `Authorization`, against the service's real `SecurityConfig` + `SharedSecurityAutoConfiguration` → status 200 and `Access-Control-Allow-Origin` equal to the origin; in the four services with a handler, `verifyNoInteractions` on its collaborator | test |
| AC2 | `<Service>DenyByDefaultTest.CorsPreflight#bareOptionsIs401` in the same five classes, same route: `OPTIONS` with no headers → 401 | test |
| AC3 | `NotificationDenyByDefaultTest#anUnmappedPathRefusesTheThreeCallers`: `beforeAnyRequest` holds only `TRACE`, `OPTIONS` is among the 7 methods asserted with all four callers (401, purpose token 401/403); Javadoc no longer calls it a deviation. On the branch since #0-199 commit 1bd4d2a7, unchanged here | test (existing) |
| AC4 | `README.md` "Infrastructure Hardening", HTTP bullet: the sentence after `(backlog #0-199, ADR-0049; SharedSecurityAutoConfigurationCorsTest)` names `UserControllerSecurityTest.CorsPreflight` and the five `CorsPreflight` classes (backlog #0-200) | reviewer-checked |
| AC5 | `./mvnw verify -pl incident-service,ingestion-service,notification-service,oncall-service,postmortem-service -am` | command |

Test run: `./mvnw verify -pl incident-service,ingestion-service,notification-service,oncall-service,postmortem-service -am` — BUILD SUCCESS,
JaCoCo "All coverage checks have been met" in every module; tests run, 0 failures, 0 errors, 0 skipped: ingestion-service
207, incident-service 269, notification-service 390, postmortem-service 131, oncall-service 147 (shared, built by `-am`,
504). Each "OPTIONS preflight" class ran 2 tests. The session could not write to `.ai/runs/0-200/` (permission denied),
so no log is stored there; the autopilot's `scripts/factory/run-tests.sh` gate writes its own.
