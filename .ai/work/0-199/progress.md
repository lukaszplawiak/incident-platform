# 0-199 `OPTIONS` is permitted on every path, so a method-less mapping runs its handler for an anonymous caller

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-199-options-preflight-cors-filter; base 424fb2c513b9a8b91c449517b5c706074b7862d3
NEXT: architect
- [architect] plan: buildCommonSecurity applies .cors(withDefaults()) and drops the OPTIONS permitAll; shared corsConfigurationSource conditional on its name; new SharedSecurityAutoConfigurationCorsTest (WebApplicationContextRunner + MockMvc with springSecurityFilterChain, real JWT, test-only method-less and explicit-OPTIONS controllers with a per-context invocation recorder); UserControllerSecurityTest.CorsPreflight rewritten (AC10); NotificationDenyByDefaultTest's OPTIONS pin moved into the anyRequest set (ADR-0049); comment-only Javadoc in six SecurityConfigs; README Infrastructure Hardening, .ai/context/architecture.md:14, BACKLOG #0-196/#0-197/#0-198 OPTIONS paragraphs; ADR-0049 written and indexed, ADR-0048 Status note; ADR: 0049 (Proposed); modules: shared, auth-service, incident-service, ingestion-service, notification-service, oncall-service, postmortem-service, root; rules: SEC-12, SEC-10, ARC-10, ARC-03, ARC-05, GEN-01, GEN-06, GEN-08, GEN-20, GEN-22, GEN-24, GEN-07, DOC-01, DOC-10, DOC-11, DOC-20
NEXT: implementer
