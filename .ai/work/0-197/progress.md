# #0-197 auth-service's route walks exempt their routes through `PUBLIC_PATHS`

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-197-auth-walks-literal-exempt-set; base bba3cc0100239ae28f76092886cae41efc00a15c
NEXT: architect
- [architect] plan: fix ApiKeyDenyByDefaultTest and IntrospectionTokenDenyByDefaultTest in place in IngestionDenyByDefaultTest's shape — literal `EXEMPT` Map ("<METHOD> <pattern>" or bare pattern -> reason; `/**` keys matched with PathPatternParser, no `startsWith`, no `PUBLIC_PATHS`), ApiKey's literal `KEY_ROUTES` (six exact TeamController patterns -> reason), every RequestMethod for a method-less mapping (TRACE pinned 400, StrictHttpFirewall), failures name METHOD pattern -> status, blank-reason and rule-3 matching tests, ApiKey's @Nested OVERRIDE negative context (test-only @RestController + a chain config that is deliberately not @Configuration, PlatformRateLimiterTest precedent: AuthServiceApplication's plain @ComponentScan picks up nested test classes); README "Infrastructure Hardening" #0-191 entry and ADR-0048 Context/Alternatives (AC6, authorised by the item); ADR: none; modules: auth-service, root; rules: SEC-10, SEC-14, SEC-12, GEN-01, GEN-03, GEN-08, GEN-20, GEN-24, ARC-02, ARC-05, DOC-01, DOC-10, DOC-11
NEXT: implementer
