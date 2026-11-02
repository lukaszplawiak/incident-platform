# Proofs: 0-199

Each acceptance criterion and the evidence that shows it (`.ai/rules/acceptance.md`). No pasted logs and
no secrets: the full output is in `.ai/runs/<item>/` (gitignored), named here.

| Criterion | Evidence | Kind |
|---|---|---|
| AC1 | `SharedSecurityAutoConfigurationCorsTest#defaultChainHasCorsFilterBeforeAuthorization` checks `defaultSecurityFilterChain`: a `CorsFilter` is present at an index below `JwtAuthFilter` and below `AuthorizationFilter` | test |
| AC2 | `SharedSecurityAutoConfigurationCorsTest#allowedPreflightIs200WithoutTokenAndInvokesNoHandler` and `#sharedSourceIsTheNamedBeanEvenWithMvcFirst`: a preflight from `security.cors.allowed-origins` gets 200 and `Access-Control-Allow-Origin: http://localhost:4200` through the shared bean | test |
| AC3 | `SharedSecurityAutoConfigurationCorsTest#allowedPreflightIs200WithoutTokenAndInvokesNoHandler`: a preflight with no token gets 200 + ACAO on the method-less and on the explicit `OPTIONS` mapping; both `InvocationRecorder` counters stay 0 | test |
| AC4 | `SharedSecurityAutoConfigurationCorsTest#foreignPreflightIs403`: Origin `https://evil.example` gets 403, no ACAO, counters 0 | test |
| AC5 | `SharedSecurityAutoConfigurationCorsTest#bareAnonymousOptionsIs401OnBothMappings`: a bare `OPTIONS` gets 401 on both mappings. `#optionsWithOriginButNoRequestMethodIsNotAPreflight`: an allowed Origin with no ACRM gets 401, a foreign one 403. Counters stay 0 in both tests | test |
| AC6 | `SharedSecurityAutoConfigurationCorsTest#authenticatedOptionsReachesExplicitHandler`: a real Bearer JWT gets 204 from the explicit handler, and its counter is 1 | test |
| AC6b | `SharedSecurityAutoConfigurationCorsTest#authenticatedOptionsOnMethodlessMappingIsAnsweredByMvc`: a real Bearer JWT gets 200 with `Allow` containing GET, POST and OPTIONS, and the method-less counter is 0 | test |
| AC7 | `SharedSecurityAutoConfigurationCorsTest#traceIs400FromFirewall`; `NotificationDenyByDefaultTest#anUnmappedPathRefusesTheThreeCallers` (TRACE pinned at 400) | test |
| AC8 | `SharedSecurityAutoConfigurationCorsTest#serviceSourceOnTheChainWins`: a chain from `buildCommonSecurity(...).cors(c -> c.configurationSource(custom))` answers a preflight from `https://custom.example` with 200 and one from `http://localhost:4200` with 403 | test |
| AC9 | `SharedSecurityAutoConfiguration.buildCommonSecurity`: the `cors(Customizer.withDefaults())` comment, with no `requestMatchers(HttpMethod.OPTIONS ...)`; the class Javadoc's "Also fixes" bullet | reviewer-checked |
| AC10 | `UserControllerSecurityTest.CorsPreflight#allowedPreflightIs200WithAllowOrigin` and `#bareOptionsIs401` | test |
| AC11 | ADR-0049, ADR-0048's Status line, `.ai/decisions/README.md` (architect, commit 29289113) | reviewer-checked |
| AC12 | README "Infrastructure Hardening": the HTTP entry states the control, and the #0-199 gap bullet is removed | reviewer-checked |
| AC13 | BACKLOG.md: the "OPTIONS (#0-199)." paragraphs of #0-196, #0-198 and #0-197 | reviewer-checked |
| AC14 | `./mvnw verify` on the nine modules | command |
| AC15 | The six `SecurityConfig` class Javadocs. `git diff` shows only comment lines changed in those files | reviewer-checked |
| AC16 | `SharedSecurityAutoConfigurationCorsTest#sharedSourceIsTheNamedBeanEvenWithMvcFirst`: with `@EnableWebMvc` registered first, `mvcHandlerMappingIntrospector` is a `CorsConfigurationSource`, the bean named `corsConfigurationSource` is the shared `UrlBasedCorsConfigurationSource`, and the preflight gets 200. `#serviceBeanNamedCorsConfigurationSourceReplacesShared`: a bean of that name is the one used. Javadoc in `SharedSecurityAutoConfiguration`: class paragraph and `corsConfigurationSource()` | test + reviewer-checked |

Red before the fix: `SharedSecurityAutoConfigurationCorsTest` was run on the unchanged production code, and 5 of its
11 tests failed (AC1, AC3, AC16, both AC5 tests). That run was not saved to a file.

Test run: `./mvnw verify -pl shared,service-parent,auth-service,ingestion-service,incident-service,notification-service,escalation-service,postmortem-service,oncall-service`. Not seen by the implementer: its background run was cut off by its turn limit (run
wf_c1597fde-23e) partway through auth-service, every test up to there passing, and no log was saved. The
result is the test gate's (`scripts/factory/run-tests.sh`) of the resumed run.
