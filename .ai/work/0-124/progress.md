# 0-124 notification-service does not require STARTTLS before sending SMTP credentials

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-124-notification-starttls-required; base 001038e03917e9cfd075bdfd832ff13154fd15df
NEXT: architect
- [architect] plan: add `starttls.required: true` to notification-service application.yml (as auth-service), the compose `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED: "false"` for notification-service, comments in application.yml and the dev kustomization, one README "Infrastructure Hardening" line; new `config/SmtpTlsDefaultsTest` (ApplicationYml.only + Binder, env overrides as a `systemEnvironment`-named SystemEnvironmentPropertySource as in StructuredLoggingGuardTest); ADR: none; modules: notification-service, docker, k8s, root; rules: GEN-01, GEN-03, GEN-06, GEN-20, GEN-22, GEN-24, ARC-02, ARC-05, SEC-30, DOC-01, DOC-10, DOC-11, K8S-03
NEXT: implementer
- [implementer r1] implemented: application.yml starttls.required + comment, compose override, kustomization comment, README bullet, SmtpTlsDefaultsTest; notification-service tests pass
- [implementer rules] GEN-01: SmtpTlsDefaultsTest both methods | GEN-03: exact true/false per key | GEN-06: shippedFileRequiresStartTls fails without required | GEN-20: application.yml comment, test Javadoc | GEN-22: config test package | GEN-24: only item files | ARC-02: ApplicationYml + systemEnvironment pattern | ARC-05: patch body untouched | SEC-30: only false overrides | DOC-01: README bullet | DOC-10: kustomization comment | DOC-11: application.yml comment | K8S-03: comment-only edit
