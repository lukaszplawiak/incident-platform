# 0-42 Kubernetes `MAIL_HOST` points at a `mailhog` that does not exist

Append-only. One line per step: `- [<stage> r<round>] <result>` — never rewrite an earlier line. The
autopilot reads the last `NEXT:` line to resume an interrupted run.

- [picker] picked; branch fix/0-42-k8s-mailpit-dev-mail-host; base 87aace9eb51a02c9ba243c8ca6f5642693a82fe5
NEXT: architect
- [architect] plan: Mailpit Deployment+ClusterIP Service in k8s/overlays/dev/mailpit.yml (compose tag v1.31.2, hardened securityContext, tcp/http probes), dev app-config patch MAIL_HOST/MAIL_PORT + 3 SPRING_MAIL_PROPERTIES_* "false", base MAIL_HOST commented .local placeholder (port 587), dev Deployment patches narrowed to component=backend, check-k8s-mail.sh (kubectl render + Ruby YAML) with mutation test-k8s-mail.sh in validate-k8s-manifests, comments and README/infrastructure.md updated; ADR: 0026 (Proposed); modules: k8s, ci, notification-service, root; rules: K8S-03, K8S-04, K8S-05, K8S-06, DOC-01, DOC-10, DOC-11, DOC-30, GEN-01, GEN-03, GEN-24, ARC-02, ARC-05, SEC-07, SEC-30
NEXT: implementer
- [implementer r1] BLOCKED: AC9 and plan steps 5-7 need .github/scripts/check-k8s-mail.sh, test-k8s-mail.sh and a ci.yml step; .github/ is out of scope for the implementer (implementer.md, implementation.md) and denied by settings.autopilot.json (Edit/Write ./.github/**). No code changed; proposed split in handoff.md.
