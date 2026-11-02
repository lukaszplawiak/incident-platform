# 0-199 `OPTIONS` is permitted on every path, so a method-less mapping runs its handler for an anonymous caller

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-199-options-preflight-cors-filter; base 424fb2c513b9a8b91c449517b5c706074b7862d3
NEXT: architect
- [architect] plan: buildCommonSecurity applies .cors(withDefaults()) and drops the OPTIONS permitAll; shared corsConfigurationSource conditional on its name; new SharedSecurityAutoConfigurationCorsTest (WebApplicationContextRunner + MockMvc with springSecurityFilterChain, real JWT, test-only method-less and explicit-OPTIONS controllers with a per-context invocation recorder); UserControllerSecurityTest.CorsPreflight rewritten (AC10); NotificationDenyByDefaultTest's OPTIONS pin moved into the anyRequest set (ADR-0049); comment-only Javadoc in six SecurityConfigs; README Infrastructure Hardening, .ai/context/architecture.md:14, BACKLOG #0-196/#0-197/#0-198 OPTIONS paragraphs; ADR-0049 written and indexed, ADR-0048 Status note; ADR: 0049 (Proposed); modules: shared, auth-service, incident-service, ingestion-service, notification-service, oncall-service, postmortem-service, root; rules: SEC-12, SEC-10, ARC-10, ARC-03, ARC-05, GEN-01, GEN-06, GEN-08, GEN-20, GEN-22, GEN-24, GEN-07, DOC-01, DOC-10, DOC-11, DOC-20
NEXT: implementer
- [implementer rules] SEC-12: SharedSecurityAutoConfiguration.java:409 `.cors(Customizer.withDefaults())` in every chain from buildCommonSecurity; anyRequest rules unchanged in all chains; only a valid preflight is unauthenticated — SharedSecurityAutoConfigurationCorsTest#allowedPreflightIs200WithoutTokenAndInvokesNoHandler, #bareAnonymousOptionsIs401OnBothMappings
- [implementer rules] SEC-10: no route requirement changed; the permitAll for OPTIONS is removed (SharedSecurityAutoConfiguration.java:383-409); deny-by-default walks green in the verify run (Incident, Ingestion, Notification, Oncall, Postmortem, ApiKey, IntrospectionToken)
- [implementer rules] ARC-10: no new SecurityFilterChain in main code; the six .cors(cors -> cors.configurationSource(...)) calls unchanged (Javadoc only); the AC8 override pinned by #serviceSourceOnTheChainWins
- [implementer rules] ARC-03: one change in shared, no service branch; escalation's default chain gains the CorsFilter (#defaultChainHasCorsFilterBeforeAuthorization)
- [implementer rules] ARC-05: the code matches ADR-0049 Decision points 1-4 (withDefaults, name-based condition at :471, override, OPTIONS answers); ADR-0049 not edited
- [implementer rules] GEN-01: CorsFilter present, name condition, override, 401 on a bare OPTIONS, 403 foreign: the 11 tests of SharedSecurityAutoConfigurationCorsTest; 5 of them were red on the code before the change
- [implementer rules] GEN-06: #bareAnonymousOptionsIs401OnBothMappings and #optionsWithOriginButNoRequestMethodIsNotAPreflight were red before the fix (200 for an anonymous OPTIONS)
- [implementer rules] GEN-07: InvocationRecorder is a bean per WebApplicationContextRunner context (SharedSecurityAutoConfigurationCorsTest), no static state
- [implementer rules] GEN-08: UserControllerSecurityTest.CorsPreflight (AC10) and NotificationDenyByDefaultTest#anUnmappedPathRefusesTheThreeCallers (AC11/ADR-0049) are in handoff.md "Tests changed and why"
- [implementer rules] GEN-20: the old "safe to exempt unconditionally" comment is replaced where it lived, at SharedSecurityAutoConfiguration.java:383-408, and says why it was reversed (#0-199)
- [implementer rules] GEN-22: shared/src/test/.../security/SharedSecurityAutoConfigurationCorsTest.java; the helpers (CorsTestConfig, CustomSourceChainConfig, InvocationRecorder, MethodlessController, ExplicitOptionsController) have no Test suffix
- [implementer rules] GEN-24: only the files the plan lists; in the six SecurityConfigs only Javadoc changed
- [implementer rules] DOC-01: README "Infrastructure Hardening" HTTP entry; #0-199 gap bullet removed
- [implementer rules] DOC-10: README gap removed, .ai/context/architecture.md:14 rewritten, BACKLOG #0-196/#0-197/#0-198 OPTIONS paragraphs; git grep finds no other current mention of the OPTIONS permitAll or the type-based condition outside ADR context/history
- [implementer rules] DOC-11: SharedSecurityAutoConfiguration class Javadoc (CORS bullet, the conditional bean paragraph, Also fixes), corsConfigurationSource() Javadoc, six SecurityConfig Javadocs, NotificationDenyByDefaultTest Javadoc, UserControllerSecurityTest.CorsPreflight Javadoc
- [implementer rules] DOC-20: buildCommonSecurity's Javadoc example (two-argument call, anyRequest().authenticated()) is pre-existing and was not changed; reported in handoff.md "Noticed, not touched"
