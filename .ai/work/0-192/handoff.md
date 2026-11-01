# Handoff: 0-192

Facts only. No reasoning about why the change is good: reviewers judge it themselves. Updated by the
implementer after every round.

## Changed
- `oncall-service/src/test/java/com/incidentplatform/oncall/api/OncallDenyByDefaultTest.java` (new): route walk
  through the real `SecurityConfig` (nested `@SpringBootApplication` scanning `com.incidentplatform.oncall` plus
  `shared.security/exception/observability`; real `JwtUtils`, `SharedSecurityAutoConfiguration`); EXEMPT `/error`;
  anonymous 401, purpose token 401/403, `ApiKey ipl_…`/`Bearer ipl_…` 401; enumeration >= 9 with
  `GET /api/v1/oncall/current/by-user/{userId}` and `GET /api/v1/oncall/schedules`; positive control (RESPONDER
  token, `/current/by-user/{uuid}` -> 403); `OpenRoute` negative case.
- `postmortem-service/src/test/java/com/incidentplatform/postmortem/api/PostmortemDenyByDefaultTest.java` (new): same
  shape, own nested `TestApplication` scanning `com.incidentplatform.postmortem`; purpose-token audience a local
  `POSTMORTEM_SERVICE`; enumeration >= 4 with `GET /api/v1/postmortems` and `GET /api/v1/postmortems/incident/{incidentId}`,
  no route under `/v3/api-docs` or `/swagger-ui`; positive control (RESPONDER, `/incident/{uuid}` -> not 401).
- `notification-service/src/test/java/com/incidentplatform/notification/api/NotificationDenyByDefaultTest.java` (new):
  nested `TestApplication` scanning `com.incidentplatform.notification` with
  `@EnableConfigurationProperties(NotificationChannelProperties.class)`; real `SlackSignatureVerifier` imported;
  `notification.channels.slack.signing-secret` set to `test-signing-secret-32chars-long` (the value
  `SlackSignatureVerifierTest` uses). EXEMPT `/error` and `/api/v1/slack/actions`. Tests: the walk of non-exempt
  routes (empty today); an unmapped path `/api/v1/test-only/unmapped` with every `RequestMethod`: the six that reach
  `anyRequest()` get AC1's answers, `OPTIONS` (permitted on `/**` by `buildCommonSecurity`) is pinned to 404 or 401,
  `TRACE` (StrictHttpFirewall) to 400; unsigned Slack POST -> 401, `processAction` never called; correctly signed
  (HMAC-SHA256, timestamp now) -> 200, `processAction("{\"type\":\"block_actions\"}")`; EXEMPT keys exact and
  `POST /api/v1/slack/actions` in the unfiltered enumeration; positive control on the unmapped path -> not 401;
  `OpenRoute` negative case.
- `OncallScheduleControllerSecurityTest`, `PostmortemControllerSecurityTest`, `OpenApiDocumentTest`: one
  `@ContextConfiguration` each (its own `TestApplication` / `PostmortemApiTestApplication`), the import, and one
  Javadoc sentence with the reason (backlog #0-192). No assertion changed.
- `README.md` "Infrastructure Hardening", the #0-191 entry: names the three tests; replaces "notification, oncall and
  postmortem follow in backlog #0-192".
- No production class, no `shared`, no config key, no endpoint, no migration.

## How to verify
- `./mvnw test -pl notification-service,oncall-service,postmortem-service -am` — run by the implementer: BUILD
  SUCCESS, every test of the three modules and of `shared` passed.
- `./mvnw test -pl oncall-service -am -Dtest='OncallDenyByDefaultTest*' -Dsurefire.failIfNoSpecifiedTests=false`
  (likewise `PostmortemDenyByDefaultTest*`, `NotificationDenyByDefaultTest*`): the new tests with their `OpenRoute`.

## Tests changed and why
- `OncallScheduleControllerSecurityTest`, `PostmortemControllerSecurityTest`, `OpenApiDocumentTest`: only an added
  `@ContextConfiguration` naming the configuration they found by package search before; the new nested
  `@SpringBootConfiguration` classes in `oncall.api` / `postmortem.api` would make that search fail with "Found
  multiple @SpringBootConfiguration" (AC1: each new test has its own nested application). Assertions unchanged.

## Deliberately out of scope
- incident's and ingestion's tests and their README text: backlog #0-196, #0-198.
- `ApiKeyAuthFilter` in the forked chains: backlog #0-193 (the tests pin 401 either way).
- A `ServiceNames` constant for postmortem/notification: `shared` is not changed; local constants are used.

## Noticed, not touched
- ADR-0048 rule 2 (every `RequestMethod` for a mapping with no method condition): `OPTIONS` is permitted on `/**` by
  `SharedSecurityAutoConfiguration.buildCommonSecurity` (line ~406), and `TRACE` is rejected with 400 by
  `StrictHttpFirewall` before any chain. A future method-less non-exempt mapping would fail the walk on both (`OPTIONS`
  rightly, as the handler would run for an anonymous caller; `TRACE` with a 400 that is a refusal). Today no walked
  route in the three services is method-less (only the exempt `/error`). — proposed backlog item: ADR-0048 decides how
  the walk treats `OPTIONS` and `TRACE` on a method-less mapping.
- The shared `ApiKeyAuthFilter` bean also runs as a plain servlet filter in these web slices (an `OPTIONS` with
  `ApiKey ipl_…` gets 401 after the chain permitted it, `Bearer ipl_…` gets 404); whether Spring Boot's filter
  registration does the same in the deployed services was not checked. — proposed backlog item: fold into #0-193.

## Follow-up needed

## Disputed
