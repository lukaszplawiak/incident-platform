# Handoff: 0-200

Facts only. No reasoning about why the change is good: reviewers judge it themselves. Updated by the
implementer after every round.

## Changed
- `incident-service/src/test/java/com/incidentplatform/incident/api/IncidentDenyByDefaultTest.java`: new `@Nested`
  class `CorsPreflight` (inherits the enclosing context, no `@NestedTestConfiguration(OVERRIDE)`), on
  `/api/v1/incidents`: `allowedPreflightIs200WithAllowOrigin` (Origin `http://localhost:4200`,
  `Access-Control-Request-Method: GET`, `Access-Control-Request-Headers: Authorization`, no token → 200,
  `Access-Control-Allow-Origin` equals the origin, `verifyNoInteractions(incidentQueryService)`) and
  `bareOptionsIs401` (bare `OPTIONS` → 401).
- `ingestion-service/.../api/IngestionDenyByDefaultTest.java`: the same pair on `/api/v1/alerts/prometheus`
  (`POST /api/v1/alerts/{source}`), request method `POST`; `verifyNoInteractions(alertIngestionService,
  rateLimitingService)`.
- `oncall-service/.../api/OncallDenyByDefaultTest.java`: the same pair on `/api/v1/oncall/schedules`, request
  method `GET`; `verifyNoInteractions(oncallScheduleService)`.
- `postmortem-service/.../api/PostmortemDenyByDefaultTest.java`: the same pair on `/api/v1/postmortems`, request
  method `GET`; `verifyNoInteractions(postmortemService)`.
- `notification-service/.../api/NotificationDenyByDefaultTest.java`: the same pair on `UNMAPPED_PATH`
  (`/api/v1/test-only/unmapped`, the `anyRequest()` rule), request method `POST`; no collaborator check (no handler
  maps the path; the Javadoc says so). The class Javadoc gains one sentence pointing to `CorsPreflight`.
- `README.md` "Infrastructure Hardening", HTTP bullet: names the per-service preflight tests (auth's
  `UserControllerSecurityTest.CorsPreflight` and the five `CorsPreflight` classes) and that escalation-service's
  `defaultSecurityFilterChain` is covered by `SharedSecurityAutoConfigurationCorsTest`.
- No production code, no `shared`, no `SecurityConfig`, no ADR, no config key.

## How to verify
- `./mvnw verify -pl incident-service,ingestion-service,notification-service,oncall-service,postmortem-service -am`
  (`-am` builds `shared` in the reactor when it is not in the local repository).
- One class: `./mvnw test -pl incident-service -am -Dtest=IncidentDenyByDefaultTest -Dsurefire.failIfNoSpecifiedTests=false`
  (likewise `IngestionDenyByDefaultTest`, `NotificationDenyByDefaultTest`, `OncallDenyByDefaultTest`,
  `PostmortemDenyByDefaultTest`); the nested class shows as "OPTIONS preflight".

## Tests changed and why
- No existing assertion changed. The five classes only gain a nested class (and imports); notification's class
  Javadoc gains one sentence. `NotificationDenyByDefaultTest#anUnmappedPathRefusesTheThreeCallers` and its Javadoc
  are untouched (AC3 already held since #0-199, commit 1bd4d2a7).

## Deliberately out of scope
- AC3: already as required on the branch since #0-199 commit 1bd4d2a7 (`beforeAnyRequest` holds only `TRACE`,
  `OPTIONS` is checked with all four callers, the Javadoc no longer calls it a deviation); cited, not re-edited.
- escalation-service: no chain of its own; its `defaultSecurityFilterChain` is covered by
  `SharedSecurityAutoConfigurationCorsTest` (#0-199).
- The walks' `OPTIONS` handling in incident/ingestion/oncall/postmortem: #0-196, #0-198.

## Noticed, not touched
- none

## Follow-up needed

## Disputed
- none
