# Handoff: 0-199

Facts only. No reasoning about why the change is good: reviewers judge it themselves. Updated by the
implementer after every round.

## Changed
- `shared/.../security/SharedSecurityAutoConfiguration.java`:
  - `buildCommonSecurity` no longer calls `.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll())`.
    It calls `.cors(Customizer.withDefaults())` in its place. The "safe to exempt unconditionally" comment is replaced
    where it lived by one that cites backlog #0-199 and ADR-0049. The `HttpMethod` import is removed and
    `Customizer` is imported.
  - `corsConfigurationSource()` is `@ConditionalOnMissingBean(name = "corsConfigurationSource")`. Before, it was
    `@ConditionalOnMissingBean(CorsConfigurationSource.class)`.
  - The class Javadoc changed in three places: the CORS bullet, the paragraph on the conditional CORS bean, and the
    "Also fixes" bullet on preflights. The `corsConfigurationSource()` Javadoc changed too. All four cite #0-199.
- `shared/src/test/.../security/SharedSecurityAutoConfigurationCorsTest.java` (new): 11 tests. They use a
  `WebApplicationContextRunner` with `@EnableWebMvc @EnableWebSecurity` and two test-only controllers under
  `/api/v1/test-only/`: one method-less, one `method = OPTIONS`. An `InvocationRecorder` bean per context counts the
  handler calls. Requests go through MockMvc with the `springSecurityFilterChain` bean. The authenticated caller sends
  a real JWT from `JwtUtils`.
- `auth-service/src/test/.../api/UserControllerSecurityTest.java`: the `CorsPreflight` nested class is rewritten (see
  below).
- `notification-service/src/test/.../api/NotificationDenyByDefaultTest.java`: `anUnmappedPathRefusesTheThreeCallers`
  and its Javadoc changed (see below).
- Class Javadoc only, in the `SecurityConfig` of auth, incident, ingestion, notification, oncall and postmortem. Each
  now says that `buildCommonSecurity` applies CORS (#0-199, ADR-0049) and that the existing
  `.cors(cors -> cors.configurationSource(corsConfigurationSource))` names the source explicitly, as an override. No
  code line changed in these classes.
- `README.md` "Infrastructure Hardening": the HTTP entry states the control. The "Not done yet > Application" bullet
  for #0-199 is removed.
- `.ai/context/architecture.md:14` now states three facts. `buildCommonSecurity` applies CORS. The shared source
  backs off only for a bean of its name. A chain not built from `buildCommonSecurity` has a `CorsFilter` only if it
  calls `.cors(...)` itself.
- `BACKLOG.md`: the three "OPTIONS (#0-199)." paragraphs of #0-196, #0-198 and #0-197 now state the expectation.
  They no longer say "until #0-199 decides".
- Not changed by the implementer: ADR-0049, ADR-0048's Status line, `.ai/decisions/README.md`. The architect
  committed these in 29289113, and the code matches ADR-0049's Decision points 1-5.
- No endpoint, topic, migration or config key changed.

## How to verify
- `./mvnw test -pl shared -Dtest=SharedSecurityAutoConfigurationCorsTest`: AC1-AC8 and AC16. On the code before
  this change, 5 of its 11 tests failed: AC1 (no `CorsFilter`), AC16 (`No bean named 'corsConfigurationSource'`),
  AC3 (preflight 403), and the two AC5 tests (anonymous `OPTIONS` answered 200).
- `./mvnw test -pl auth-service -Dtest=UserControllerSecurityTest`: AC10.
- `./mvnw test -pl notification-service -Dtest=NotificationDenyByDefaultTest`: `OPTIONS` is among the `anyRequest()`
  methods, and `TRACE` stays at 400.
- `./mvnw verify -pl shared,service-parent,auth-service,ingestion-service,incident-service,notification-service,escalation-service,postmortem-service,oncall-service`: AC14.

## Tests changed and why
- `UserControllerSecurityTest.CorsPreflight`: `optionsRequestIsNotBlockedByAuthentication` is replaced by two tests.
  It expected a bare `OPTIONS /api/v1/users` with no principal to be neither 401 nor 403. AC10 and ADR-0049
  point 4 make that request 401. The new tests are:
  - `allowedPreflightIs200WithAllowOrigin`: a real preflight gets 200 with `Access-Control-Allow-Origin`.
  - `bareOptionsIs401`: a bare `OPTIONS` gets 401.

  The nested class now carries `@ImportAutoConfiguration(SharedSecurityAutoConfiguration.class)` and its own
  `@Autowired MockMvc corsMockMvc`. The plan expected the shared source to reach this slice through a component
  scan. It does not: `AuthApiTestApplication` scans `com.incidentplatform.shared.security`, but the scan leaves out
  auto-configurations. In the outer context, `SecurityConfig` gets `mvcHandlerMappingIntrospector` as its source,
  and the preflight got 403 (seen in a run before this fix). The enclosing class's `mockMvc` comes from that outer
  context, so the nested class uses its own. The running service gets the shared source from `AutoConfiguration.imports`.
- `NotificationDenyByDefaultTest.anUnmappedPathRefusesTheThreeCallers`: `OPTIONS` is removed from the pinned
  `beforeAnyRequest` map, where it accepted `404 || 401`. It is now checked with the same predicates as the other
  methods: 401 anonymous, 401/403 purpose token, 401 for both key forms. The size assertion goes from 6 to 7 and
  checks that `OPTIONS` is in the set. `TRACE` stays pinned at 400. The reasons are AC11 and ADR-0049 ("a
  `404 || 401` for `OPTIONS` is a deviation to remove"). The test now asserts more than before.

## Deliberately out of scope
- Each service's own preflight test: backlog #0-200 (ADR-0049 Backlog line).
- The walks of #0-196, #0-197 and #0-198. Only their BACKLOG paragraphs changed (AC13).
- Moving #0-199 to BACKLOG-DONE.md: the shipper's job.

## Noticed, not touched
- `SharedSecurityAutoConfiguration.buildCommonSecurity` Javadoc example (`:335-348` before this change). It calls
  `buildCommonSecurity(http, filter)` with two arguments, but the method takes three. It also ends in
  `anyRequest().authenticated()` where the platform uses `authenticatedExceptPurposeTokens()`. This is pre-existing
  (DOC-20). Proposed backlog item: correct the example to the three-argument call and
  `authenticatedExceptPurposeTokens()`.
- `incident-service/.../config/DevSecurityConfig.java:45-55`: the `/dev/**` chain (local/dev profiles only) is not
  built from `buildCommonSecurity` and calls no `.cors(...)`, so it has no `CorsFilter`. A browser preflight to
  `/dev/token` from the frontend is not answered with CORS headers. Proposed backlog item: decide whether the dev
  chain applies CORS.

## Follow-up needed

## Disputed
