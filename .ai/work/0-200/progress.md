# 0-200 Each service's CORS preflight still passes once the `OPTIONS` rule is gone

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-200-per-service-cors-preflight-tests; base 091a55636b7a20d27b222fb7c63668f7d33998ff
NEXT: architect
- [architect] plan: a @Nested CorsPreflight (inherited context, no OVERRIDE) in each of Incident/Ingestion/Notification/Oncall/PostmortemDenyByDefaultTest, whose context already has the real SecurityConfig and SharedSecurityAutoConfiguration: an allowed preflight with no token gets 200 and Access-Control-Allow-Origin, a bare OPTIONS gets 401 (notification on its unmapped anyRequest() path: no authenticated controller route); AC3 already holds since #0-199 (1bd4d2a7), cited not re-edited; README "Infrastructure Hardening" HTTP entry names the tests; ADR: none; modules: incident-service, ingestion-service, notification-service, oncall-service, postmortem-service, root; rules: SEC-12, SEC-10, ARC-05, ARC-02, GEN-01, GEN-03, GEN-08, GEN-20, GEN-22, GEN-24, DOC-01, DOC-11
NEXT: implementer
