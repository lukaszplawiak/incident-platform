# #0-197 auth-service's route walks exempt their routes through `PUBLIC_PATHS`

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-197-auth-walks-literal-exempt-set; base bba3cc0100239ae28f76092886cae41efc00a15c
NEXT: architect
- [architect] plan: fix ApiKeyDenyByDefaultTest and IntrospectionTokenDenyByDefaultTest in place in IngestionDenyByDefaultTest's shape — literal `EXEMPT` Map ("<METHOD> <pattern>" or bare pattern -> reason; `/**` keys matched with PathPatternParser, no `startsWith`, no `PUBLIC_PATHS`), ApiKey's literal `KEY_ROUTES` (six exact TeamController patterns -> reason), every RequestMethod for a method-less mapping (TRACE pinned 400, StrictHttpFirewall), failures name METHOD pattern -> status, blank-reason and rule-3 matching tests, ApiKey's @Nested OVERRIDE negative context (test-only @RestController + a chain config that is deliberately not @Configuration, PlatformRateLimiterTest precedent: AuthServiceApplication's plain @ComponentScan picks up nested test classes); README "Infrastructure Hardening" #0-191 entry and ADR-0048 Context/Alternatives (AC6, authorised by the item); ADR: none; modules: auth-service, root; rules: SEC-10, SEC-14, SEC-12, GEN-01, GEN-03, GEN-08, GEN-20, GEN-24, ARC-02, ARC-05, DOC-01, DOC-10, DOC-11
NEXT: implementer
- [implementer r1] both auth walks read a literal EXEMPT (ApiKey also KEY_ROUTES, six exact team patterns), send every RequestMethod to a method-less mapping (TRACE 400), name `METHOD pattern -> status`; entry/shape/rule-3 tests in both; ApiKey's @Nested OVERRIDE OpenRoute negative case; introspection's registerMapping every-method test; README #0-191 entry and ADR-0048 Context/Alternatives updated; `./mvnw test -pl auth-service -am` 1010 tests, 0 failures
- [implementer rules] SEC-10: ApiKeyDenyByDefaultTest.java:145 and IntrospectionTokenDenyByDefaultTest.java:121 literal EXEMPT with reasons, no PUBLIC_PATHS; #everyExemptEntryHasAReason
- [implementer rules] SEC-14: ApiKeyDenyByDefaultTest.java:176 KEY_ROUTES exact patterns; scope tests unchanged; #exemptEntriesNameRealRoutes pins them
- [implementer rules] SEC-12: ApiKeyDenyByDefaultTest.java:505 OpenRouteChain only in OpenRoute's OVERRIDE context, not a @Configuration (never scanned); production chain untouched
- [implementer rules] GEN-01: #exemptMatchingFollowsRule3 (both), #everyExemptEntryHasAReason (both), IntrospectionTokenDenyByDefaultTest#theWalkSendsEveryMethodToAMethodlessMapping, ApiKeyDenyByDefaultTest.OpenRoute (both tests)
- [implementer rules] GEN-03: OpenRoute tests assert hasMessageContaining "GET /api/v1/test-only/open -> 200" / "POST /api/v1/test-only/any-method -> 200"
- [implementer rules] GEN-08: handoff.md "Tests changed and why" (both everyOtherRouteIsForbidden; 403, lower bound, named routes kept; TRACE 400 only)
- [implementer rules] GEN-20: ApiKeyDenyByDefaultTest.java:108 Fixed note, OpenRoute Javadoc (no @SpringBootConfiguration), :505 OpenRouteChain Javadoc (not @Configuration), :525 TRACE pin Javadoc; IntrospectionTokenDenyByDefaultTest.java:85, :288
- [implementer rules] GEN-24: diff is the two tests, README #0-191 entry, ADR-0048 two sentences, work files; AuthApiTestApplication scan and SEC-14 text left, recorded in handoff "Noticed"
- [implementer rules] ARC-02: shape of IngestionDenyByDefaultTest (routes/assertEveryRoute/OpenRoute); /** matching by Spring's PathPatternParser, no new scheme
- [implementer rules] ARC-05: ADR-0048 rule 3 (isExempt), rule 5 (OpenRoute), rule 6 (helpers private to each test); Status and rules unchanged
- [implementer rules] DOC-01: README.md "Infrastructure Hardening" #0-191 entry, auth-service sentence
- [implementer rules] DOC-10: README auth sentence rewritten; ADR-0048 Context and Alternatives in past tense ("until #0-197")
- [implementer rules] DOC-11: class Javadoc of both tests describe the literal exempt set and every-method walk
