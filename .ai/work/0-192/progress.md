# 0-192 notification, oncall and postmortem refuse an unauthenticated caller on every route

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch `fix/0-192-deny-by-default-three-services`; base `055f7a8e541016e14002f479680be8aa636bc819`
NEXT: architect
- [architect] plan: copy IncidentDenyByDefaultTest's shape (ADR-0048 as amended: every RequestMethod for a method-less mapping, `/**` matching, positive control) into OncallDenyByDefaultTest, PostmortemDenyByDefaultTest, NotificationDenyByDefaultTest (walk + unmapped path + Slack route through the real SlackSignatureVerifier); pin `@ContextConfiguration` on OncallScheduleControllerSecurityTest, PostmortemControllerSecurityTest and OpenApiDocumentTest, which find their configuration by search and would hit "Found multiple @SpringBootConfiguration" once the new nested test applications exist; notification's EXEMPT literal is `/error` + `/api/v1/slack/actions` (AC9), AC5 asserted as "beyond `/error`, exactly the Slack route"; purpose-token audience a local constant equal to `spring.application.name` (no ServiceNames constant: shared unchanged); README "Infrastructure Hardening" entry of #0-191 names the three tests; ADR: none (ADR-0048 decides the shape); modules: notification-service, oncall-service, postmortem-service, root; rules: SEC-10, SEC-12, SEC-23, SEC-30, SEC-11, GEN-01, GEN-03, GEN-07, GEN-08, GEN-20, GEN-22, GEN-24, ARC-05, DOC-01, DOC-10
NEXT: implementer
