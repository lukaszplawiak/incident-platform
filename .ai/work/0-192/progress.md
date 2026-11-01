# 0-192 notification, oncall and postmortem refuse an unauthenticated caller on every route

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch `fix/0-192-deny-by-default-three-services`; base `055f7a8e541016e14002f479680be8aa636bc819`
NEXT: architect
- [architect] plan: copy IncidentDenyByDefaultTest's shape (ADR-0048 as amended: every RequestMethod for a method-less mapping, `/**` matching, positive control) into OncallDenyByDefaultTest, PostmortemDenyByDefaultTest, NotificationDenyByDefaultTest (walk + unmapped path + Slack route through the real SlackSignatureVerifier); pin `@ContextConfiguration` on OncallScheduleControllerSecurityTest, PostmortemControllerSecurityTest and OpenApiDocumentTest, which find their configuration by search and would hit "Found multiple @SpringBootConfiguration" once the new nested test applications exist; notification's EXEMPT literal is `/error` + `/api/v1/slack/actions` (AC9), AC5 asserted as "beyond `/error`, exactly the Slack route"; purpose-token audience a local constant equal to `spring.application.name` (no ServiceNames constant: shared unchanged); README "Infrastructure Hardening" entry of #0-191 names the three tests; ADR: none (ADR-0048 decides the shape); modules: notification-service, oncall-service, postmortem-service, root; rules: SEC-10, SEC-12, SEC-23, SEC-30, SEC-11, GEN-01, GEN-03, GEN-07, GEN-08, GEN-20, GEN-22, GEN-24, ARC-05, DOC-01, DOC-10
NEXT: implementer
- [implementer r1] added OncallDenyByDefaultTest, PostmortemDenyByDefaultTest, NotificationDenyByDefaultTest; pinned @ContextConfiguration on the three existing api tests; README #0-191 entry names the three tests; notification's unmapped path pins OPTIONS (shared preflight permit: 404/401) and TRACE (firewall: 400) apart from the six methods that reach anyRequest(); `./mvnw test -pl notification-service,oncall-service,postmortem-service -am` BUILD SUCCESS
- [implementer rules] SEC-10: EXEMPT literal with a reason per entry in each new test (OncallDenyByDefaultTest, PostmortemDenyByDefaultTest, NotificationDenyByDefaultTest `EXEMPT`); no code reads PUBLIC_PATHS
- [implementer rules] SEC-12: real SecurityConfig + SharedSecurityAutoConfiguration + real JwtUtils; `#everyRouteRefusesAnUnknownApiKey` in each test
- [implementer rules] SEC-23: NotificationDenyByDefaultTest#slackActionsWithoutSignatureIs401FromTheRealVerifier and #slackActionsCorrectlySignedIs200, SlackSignatureVerifier imported, not mocked
- [implementer rules] SEC-30: jwt.secret and signing secret are the existing obviously fake test values (`test-secret-key-minimum-64-…`, `test-signing-secret-32chars-long`)
- [implementer rules] SEC-11: positive controls use SecurityRoles.ROLE_RESPONDER in the JWT claim
- [implementer rules] GEN-01: OpenRoute negative case and aValidUserTokenGetsPastAuthentication in each test
- [implementer rules] GEN-03: enumeration guards (lower bound + named routes), Slack 401 with never(), signed 200 with the decoded payload argument
- [implementer rules] GEN-07: signed Slack request uses Instant.now() epoch seconds computed in the test
- [implementer rules] GEN-08: the three pinned tests got only @ContextConfiguration + import + one Javadoc sentence; recorded in handoff "Tests changed and why"
- [implementer rules] GEN-20: class Javadoc of each new test (backlog #0-192, ADR-0048, why notification checks an unmapped path, why the pins)
- [implementer rules] GEN-22: <Service>DenyByDefaultTest in each api test package; nested TestApplication/OpenRouteApplication carry no Test suffix
- [implementer rules] GEN-24: test code and README only; incident/ingestion untouched
- [implementer rules] ARC-05: ADR-0048 rules 1-7 copied, one private walk per test (rule 6)
- [implementer rules] DOC-01: README "Infrastructure Hardening" #0-191 entry names the three tests
- [implementer rules] DOC-10: "notification, oncall and postmortem follow in backlog #0-192" removed from README
