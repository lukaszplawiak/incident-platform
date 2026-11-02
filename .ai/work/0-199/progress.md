# 0-199 `OPTIONS` is permitted on every path, so a method-less mapping runs its handler for an anonymous caller

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-199-options-answered-by-corsfilter; base eea28f7d71a32031ab59e98d9bf9d1b3aca0621f
NEXT: architect
- [architect] plan: blocked — AC6 (and the method-less half of AC5/AC9/AC11/AC12's premise) contradicts Spring MVC: `RequestMethodsRequestCondition` with no methods does not match `OPTIONS` ("we handle OPTIONS transparently"), so `RequestMappingInfoHandlerMapping.handleNoMatch` answers it with `Allow` and never invokes a method-less handler, for an authenticated caller too; only an explicit `@RequestMapping(method = OPTIONS)` handler runs. AC6 as written would fail; correcting it is the owner's wording; ADR: none; modules: shared, auth-service, incident-service, ingestion-service, notification-service, oncall-service, postmortem-service, root; rules: SEC-10, SEC-12, ARC-03, ARC-05, ARC-10, GEN-01, GEN-06, GEN-08, GEN-20, GEN-24, DOC-01, DOC-10, DOC-11
NEXT: owner — restate AC6 (positive control on the explicit OPTIONS handler; for the method-less mapping an authenticated bare OPTIONS gets 200 with `Allow`, handler not invoked) and the method-less claim in the Problem, AC9, AC11 and AC12; also check AC2's premise (shared `corsConfigurationSource` is `@ConditionalOnMissingBean(CorsConfigurationSource.class)`, and Spring MVC's `mvcHandlerMappingIntrospector` is a `CorsConfigurationSource`)
- [blocked] architect found AC6 contradicts Spring MVC's implicit OPTIONS handling; the owner restates the criteria
NEXT: owner
