# 0-200 Each service's CORS preflight still passes once the `OPTIONS` rule is gone

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-200-per-service-cors-preflight-tests; base 091a55636b7a20d27b222fb7c63668f7d33998ff
NEXT: architect
- [architect] plan: a @Nested CorsPreflight (inherited context, no OVERRIDE) in each of Incident/Ingestion/Notification/Oncall/PostmortemDenyByDefaultTest, whose context already has the real SecurityConfig and SharedSecurityAutoConfiguration: an allowed preflight with no token gets 200 and Access-Control-Allow-Origin, a bare OPTIONS gets 401 (notification on its unmapped anyRequest() path: no authenticated controller route); AC3 already holds since #0-199 (1bd4d2a7), cited not re-edited; README "Infrastructure Hardening" HTTP entry names the tests; ADR: none; modules: incident-service, ingestion-service, notification-service, oncall-service, postmortem-service, root; rules: SEC-12, SEC-10, ARC-05, ARC-02, GEN-01, GEN-03, GEN-08, GEN-20, GEN-22, GEN-24, DOC-01, DOC-11
NEXT: implementer
- [implementer r1] five @Nested CorsPreflight classes (inherited context) in Incident/Ingestion/Notification/Oncall/PostmortemDenyByDefaultTest: allowed preflight 200 + Access-Control-Allow-Origin with no handler collaborator touched, bare OPTIONS 401; notification on UNMAPPED_PATH; README HTTP bullet names the tests; no production code
- [implementer rules] SEC-12: each CorsPreflight runs on its enclosing context (real SecurityConfig, real JwtUtils, @ImportAutoConfiguration(SharedSecurityAutoConfiguration)); no test chain
- [implementer rules] SEC-10: routes are authenticated ones (/api/v1/incidents, /api/v1/alerts/prometheus, /api/v1/oncall/schedules, /api/v1/postmortems, notification's anyRequest() UNMAPPED_PATH); no permitAll route used
- [implementer rules] ARC-05: expectations are ADR-0049 rule 4 (valid preflight 200 from CorsFilter, bare OPTIONS 401); no OPTIONS permitAll, no second CorsConfigurationSource
- [implementer rules] ARC-02: inheriting @Nested class, no NestedTestConfiguration(OVERRIDE), no new TestApplication or context; uses the outer mockMvc and MockitoBeans
- [implementer rules] GEN-01: allowed preflight goes 401 without CorsFilter, 403 without the shared source; bareOptionsIs401 goes red if an OPTIONS permitAll returns
- [implementer rules] GEN-03: asserts Access-Control-Allow-Origin equals the origin, not only 200; verifyNoInteractions on the handler collaborator (incident, ingestion x2, oncall, postmortem); notification has no handler for the path (Javadoc says so)
- [implementer rules] GEN-08: no existing assertion changed; anUnmappedPathRefusesTheThreeCallers untouched
- [implementer rules] GEN-20: each CorsPreflight Javadoc cites backlog #0-200 and ADR-0049 rule 4; notification's says why the unmapped path
- [implementer rules] GEN-22: nested class of the existing <Service>DenyByDefaultTest in each api test package
- [implementer rules] GEN-24: diff is the five test files and one README sentence only
- [implementer rules] DOC-01: README.md "Infrastructure Hardening" HTTP bullet, after the #0-199 sentence
- [implementer rules] DOC-11: NotificationDenyByDefaultTest class Javadoc gains one sentence on CorsPreflight; the other four class Javadocs remain true
- [implementer r1] ./mvnw verify -pl incident,ingestion,notification,oncall,postmortem -am: BUILD SUCCESS, 0 failures, coverage met in every module
