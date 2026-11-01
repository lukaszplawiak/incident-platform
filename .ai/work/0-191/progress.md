# 0-191 Every service with an API refuses an unauthenticated caller on every route

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch `fix/0-191-deny-by-default-tests`; base `d9ab907d63a0d8647700c908424c1c99ad6f3aa9`
NEXT: architect
- [architect] plan: IncidentDenyByDefaultTest (incident/api) and IngestionDenyByDefaultTest (ingestion/api) walk every RequestMappingHandlerMapping route through the real SecurityConfig for three callers (anonymous 401, purpose token 401/403, unknown key ApiKey+Bearer ipl_ 401), literal exempt map with reasons, lower-bound count + known routes, @Nested negative case with a test-only route and permissive chain, incident asserts no profile and no DevSecurityConfig; README "Infrastructure Hardening" Application bullet; ADR: 0048 (Proposed); modules: incident-service, ingestion-service, root; rules: SEC-10, SEC-12, SEC-14, SEC-30, GEN-01, GEN-03, GEN-07, GEN-20, GEN-22, GEN-24, ARC-02, ARC-10, DOC-01, DOC-40
NEXT: implementer
