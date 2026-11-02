# ADR-0049: Every chain answers a valid CORS preflight in its `CorsFilter`; every other `OPTIONS` is authenticated like any method

- **Status:** Proposed
- **Backlog:** #0-199 (states ADR-0048 rule 2's `OPTIONS` expectation); #0-200 (each service's preflight test);
  #0-196, #0-197, #0-198 (the walks that apply it)
- **Date:** 2026-11-02
- **Author:** architect agent (autopilot run 20261102-135304)
- **Reversible:** yes (one rule and one `cors(...)` call in `SharedSecurityAutoConfiguration.buildCommonSecurity`,
  one bean condition; no contract, schema or dependency changes)

## Context

`buildCommonSecurity` put `.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()` ahead of every caller's rules, so
CORS preflights (which carry no `Authorization`) were not answered 401. Its comment called that safe "unconditionally".
It is not: an explicit `@RequestMapping(method = OPTIONS)` handler runs for an anonymous caller. A method-less mapping
does not (owner's correction of #0-199, 2026-11-02): `RequestMethodsRequestCondition.getMatchingCondition`
(spring-webmvc 6.2.19) does not match an `OPTIONS` on a mapping without methods outside an `ERROR` dispatch, and the
dispatcher answers it itself with `Allow` (`HttpOptionsHandler`). No service has an explicit `OPTIONS` handler today.

Six services already call `.cors(cors -> cors.configurationSource(corsConfigurationSource))` on their own chain;
escalation-service's `defaultSecurityFilterChain` had no `CorsFilter`. The shared `corsConfigurationSource` bean was
`@ConditionalOnMissingBean(CorsConfigurationSource.class)`, and Spring MVC's `mvcHandlerMappingIntrospector` is itself
a `CorsConfigurationSource`: the shared bean existed only because `SharedSecurityAutoConfiguration` happened to be
processed before `WebMvcAutoConfiguration`.

ADR-0048 rule 2 expects 401 for an anonymous caller on every method of a walked mapping; #0-192's walk pinned
`OPTIONS` on an unmapped path as `404 || 401` (`NotificationDenyByDefaultTest`), a deviation in the test.

## Decision

1. `buildCommonSecurity` calls `.cors(Customizer.withDefaults())` and holds no `OPTIONS` rule. Every chain built from
   it has a `CorsFilter` ahead of authentication and authorization. `CorsConfigurer` (spring-security-config 6.5.11)
   takes the source set on the chain, else a bean named `corsFilter`, else a bean named `corsConfigurationSource`,
   else Spring MVC's `HandlerMappingIntrospector`.
2. The shared `corsConfigurationSource` bean is `@ConditionalOnMissingBean(name = "corsConfigurationSource")`: it
   backs off only for a bean of that name, never for the introspector. A `CorsConfigurationSource` under another name
   sits beside it and is not used (the services inject by type, resolved by the parameter name
   `corsConfigurationSource`).
3. A service's own `.cors(cors -> cors.configurationSource(x))` after `buildCommonSecurity` overrides the default (the
   same `CorsConfigurer`); the six existing calls stay as explicit overrides.
4. What a request with method `OPTIONS` answers, for ADR-0048 rule 2 and every walk:
   - a valid preflight (`Origin` from `security.cors.allowed-origins` and `Access-Control-Request-Method`): 200 from
     `CorsFilter`, which does not continue the chain, so no handler and no authentication runs;
   - a preflight from a foreign origin, or an `OPTIONS` with a foreign `Origin`: 403 from `DefaultCorsProcessor`;
   - any other `OPTIONS` (no preflight headers, or an allowed `Origin` without `Access-Control-Request-Method`): the
     same as every method, 401 for an anonymous caller (rule 2 already expected it);
   - for an authenticated caller: an explicit `method = OPTIONS` handler runs; a method-less mapping gets Spring MVC's
     implicit 200 with `Allow`, its handler not called.
   `TRACE` is 400 from `StrictHttpFirewall`, before any chain.
5. Among annotated Spring MVC mappings an anonymous `OPTIONS` could reach a handler only through an explicit
   `method = OPTIONS` mapping, never a method-less one (outside an `ERROR` dispatch, e.g. `/error`); rule 1 closes
   that path in every chain built from `buildCommonSecurity`.

## Alternatives considered

- **Keep the rule, narrowed to `CorsUtils::isPreFlightRequest`** (#0-199 option A): still a `permitAll` for a request
  the chain does not need to see, since `CorsFilter` answers a valid preflight first; and escalation's chain would
  still have no `CorsFilter`.
- **Keep the rule and forbid explicit `OPTIONS` handlers by a test or ArchUnit** (option B): leaves the wrong reason in
  the shared chain and depends on a check (#0-108) that does not exist.
- **`.cors(...)` with an explicit source inside `buildCommonSecurity`**: its signature takes no source, and every
  caller would change; `withDefaults()` finds the same bean by name.
- **Keep the type-based condition**: correct only by auto-configuration order; a context with Spring MVC configured
  first (`@EnableWebMvc` in a test) loses the shared source and every preflight gets 403.

## Consequences

- A walk (ADR-0048) expects 401 for an anonymous `OPTIONS` like any method and pins only `TRACE` (400); a `404 || 401`
  for `OPTIONS` is a deviation to remove (#0-196, #0-197, #0-198).
- A new chain built from `buildCommonSecurity` gets CORS without a `.cors(...)` call; a chain that does not call it
  (incident's `DevSecurityConfig`) still applies CORS itself (SEC-12).
- A reviewer treats a new `permitAll()` keyed on `HttpMethod.OPTIONS`, or a `CorsConfigurationSource` bean under
  another name meant to replace the shared one, as a violation of this ADR.
- A non-browser client that sends a foreign `Origin` to escalation-service now gets 403, as it already did on the six
  other services; probes, scrapes and service-to-service calls send no `Origin`.
