# 0-196 incident's route walk gets #0-192's shape

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-196-incident-route-walk-shape; base 529b8412cf720b88e08ad5454d4a46c35d2d4e11
NEXT: architect
- [architect] plan: IncidentDenyByDefaultTest copies OncallDenyByDefaultTest's shape (#0-192, ADR-0048 rules 2/3/7) — FULL tenant stub + RESPONDER positive control on GET /api/v1/incidents/{id} (not 401, findById asked with the token's tenant), EnumSet.allOf for method-less mappings with a method-less test-only route in OpenRoute (POST reported), EXEMPT = /error only, new @Nested WsRoute context (real SecurityConfig, no test chain, test-only GET /ws/test-only reported); README "Infrastructure Hardening" entry updated; ADR: none (ADR-0048 already amended for #0-196); modules: incident-service, root; rules: SEC-10, SEC-12, GEN-01, GEN-03, GEN-08, GEN-20, GEN-24, ARC-05, DOC-01, DOC-10, DOC-11
NEXT: implementer
