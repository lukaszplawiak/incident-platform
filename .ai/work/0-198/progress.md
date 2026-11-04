# 0-198 ingestion's route walk gets #0-192's shape

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch feat/0-198-ingestion-route-walk-shape; base 732700719c4b7421f397109421901e8b3b8bfe04
NEXT: architect
- [architect] plan: bring IngestionDenyByDefaultTest to ADR-0048 rules 2, 3, 7 as #0-196 did for incident (test code only): tenant FULL in @BeforeEach + RESPONDER-token positive control on POST /api/v1/alerts/{source} (403, controller not reached), verify(lookup(eq(UNKNOWN_KEY), any()), atLeastOnce()) after each unknown-key walk with clearInvocations between, incident's isExempt in place of EXEMPT.containsKey, EnumSet.allOf(RequestMethod) for a methodless mapping with an ANY_METHOD_PATH test-only route in OpenRoute; README "Infrastructure Hardening" entry of #0-191 updated; ADR: none; modules: ingestion-service, root; rules: SEC-10, SEC-12, ARC-02, ARC-05, GEN-01, GEN-03, GEN-08, GEN-20, GEN-24, DOC-01, DOC-11
NEXT: implementer
