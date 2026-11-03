# 0-196 incident's route walk gets #0-192's shape

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-196-incident-route-walk-shape; base 529b8412cf720b88e08ad5454d4a46c35d2d4e11
NEXT: architect
- [architect] plan: IncidentDenyByDefaultTest copies OncallDenyByDefaultTest's shape (#0-192, ADR-0048 rules 2/3/7) — FULL tenant stub + RESPONDER positive control on GET /api/v1/incidents/{id} (not 401, findById asked with the token's tenant), EnumSet.allOf for method-less mappings with a method-less test-only route in OpenRoute (POST reported), EXEMPT = /error only, new @Nested WsRoute context (real SecurityConfig, no test chain, test-only GET /ws/test-only reported); README "Infrastructure Hardening" entry updated; ADR: none (ADR-0048 already amended for #0-196); modules: incident-service, root; rules: SEC-10, SEC-12, GEN-01, GEN-03, GEN-08, GEN-20, GEN-24, ARC-05, DOC-01, DOC-10, DOC-11
NEXT: implementer
- [implementer r1] IncidentDenyByDefaultTest: FULL tenant stub, positive control aValidUserTokenGetsPastAuthentication, routes() walks EnumSet.allOf for method-less mappings, EXEMPT = /error only, OpenRoute#theWalkSendsEveryMethodToAMethodlessMapping, new WsRoute#theWalkReportsARestMappingUnderWs, enumeration excludes both new test-only routes; README #0-191 bullet updated; incident-service 272 tests green locally
- [implementer rules] SEC-10: IncidentDenyByDefaultTest.java:137 EXEMPT is a literal with one reasoned entry (/error), never PUBLIC_PATHS
- [implementer rules] SEC-12: SecurityConfig unchanged; WsRoute#theWalkReportsARestMappingUnderWs pins its permitAll("/ws/**") reaching a REST mapping, outer walks pin authenticatedExceptPurposeTokens()
- [implementer rules] GEN-01: every-method walk — OpenRoute#theWalkSendsEveryMethodToAMethodlessMapping (red with Set.of(GET)); no /ws exemption — WsRoute#theWalkReportsARestMappingUnderWs (red with the /ws/** entry); positive control — IncidentDenyByDefaultTest.java:232
- [implementer rules] GEN-03: aValidUserTokenGetsPastAuthentication asserts status != 401 and verify(findById(id, "acme-corp")) with arguments
- [implementer rules] GEN-08: handoff.md "Tests changed and why" ties routes() to AC2 and EXEMPT to AC3; no assertion removed
- [implementer rules] GEN-20: class Javadoc "No exemption for /ws/" (line 103), routes() Javadoc (ADR-0048 rule 2, #0-196), positive-control Javadoc (rule 7)
- [implementer rules] GEN-24: diff is IncidentDenyByDefaultTest, README #0-191 bullet, .ai/work/0-196 only
- [implementer rules] ARC-05: ADR-0048 rule 2 (EnumSet.allOf, line 492), rule 3 (EXEMPT /error, isExempt unchanged), rule 7 (positive control) kept
- [implementer rules] DOC-01: README "Infrastructure Hardening" #0-191 bullet updated in the same change
- [implementer rules] DOC-10: README no longer names incident's /ws/** exempt entry; git grep finds no other current text naming it (.ai/plan/queue.md describes the item itself)
- [implementer rules] DOC-11: class Javadoc, collaborator and TenantStatusProvider comments, routes() Javadoc, OpenRoute/OpenRouteApplication Javadoc rewritten
