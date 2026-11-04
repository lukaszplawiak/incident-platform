# 0-198 ingestion's route walk gets #0-192's shape

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch feat/0-198-ingestion-route-walk-shape; base 732700719c4b7421f397109421901e8b3b8bfe04
NEXT: architect
- [architect] plan: bring IngestionDenyByDefaultTest to ADR-0048 rules 2, 3, 7 as #0-196 did for incident (test code only): tenant FULL in @BeforeEach + RESPONDER-token positive control on POST /api/v1/alerts/{source} (403, controller not reached), verify(lookup(eq(UNKNOWN_KEY), any()), atLeastOnce()) after each unknown-key walk with clearInvocations between, incident's isExempt in place of EXEMPT.containsKey, EnumSet.allOf(RequestMethod) for a methodless mapping with an ANY_METHOD_PATH test-only route in OpenRoute; README "Infrastructure Hardening" entry of #0-191 updated; ADR: none; modules: ingestion-service, root; rules: SEC-10, SEC-12, ARC-02, ARC-05, GEN-01, GEN-03, GEN-08, GEN-20, GEN-24, DOC-01, DOC-11
NEXT: implementer
- [implementer r1] IngestionDenyByDefaultTest: positive control (RESPONDER → 403), lookup(eq(UNKNOWN_KEY)) verified after each unknown-key walk (red by mutation without addFilterBefore, reverted), incident's isExempt, every RequestMethod for a methodless mapping + OpenRoute#theWalkSendsEveryMethodToAMethodlessMapping; README entry updated; ./mvnw test -pl ingestion-service -am green (209 ingestion tests)
- [implementer rules] SEC-12: IngestionDenyByDefaultTest.java:197,202 (verify lookup after each form; red by mutation without ApiKeyAuthFilter in the chain); SecurityConfig unchanged
- [implementer rules] SEC-10: EXEMPT stays a literal with its reason, read by isExempt (IngestionDenyByDefaultTest.java:438), never PUBLIC_PATHS
- [implementer rules] ARC-02: isExempt and the walk copied verbatim from IncidentDenyByDefaultTest, no shared helper
- [implementer rules] ARC-05: ADR-0048 rules 2 (EnumSet.allOf, :427), 3 (isExempt, :438), 6 (own copy), 7 (aValidUserTokenGetsPastAuthentication, :233)
- [implementer rules] GEN-01: each new behaviour has a test (positive control :233, lookup verify :197/:202 shown red by mutation, methodless walk :340)
- [implementer rules] GEN-03: verify carries eq(UNKNOWN_KEY); positive control pins isEqualTo(403) and verifyNoInteractions(alertIngestionService)
- [implementer rules] GEN-08: no assertion removed or loosened; additions listed in handoff "Tests changed and why"
- [implementer rules] GEN-20: class Javadoc :94 (why the verify proves the chain, #0-193), routes()/EXEMPT/positive-control Javadoc cite #0-198
- [implementer rules] GEN-24: diff is the test file and README entry only
- [implementer rules] DOC-01: README.md "Infrastructure Hardening", "Every route refuses an unauthenticated caller" entry
- [implementer rules] DOC-11: "never asked" comment, "(no method: GET)" routes() Javadoc and EXEMPT Javadoc rewritten to the new behaviour
