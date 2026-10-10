# ADR-0026: Mailpit runs in the Kubernetes dev overlay only; staging and prod have no mail relay (backlog #0-42)

- **Status:** Proposed
- **Backlog:** #0-42
- **Date:** 2026-10-10
- **Author:** architect agent (autopilot run 20261010-052351); the "what" was decided by the owner in `/ready #0-42`
- **Reversible:** yes

## Context

`k8s/base/infrastructure/app-config.yml` set `MAIL_HOST: "mailhog"`, but no manifest deployed a mail server, and
auth-service (`mail.smtp.auth`, `starttls.required`) and notification-service (`mail.smtp.auth`) require SMTP auth,
which a dev catcher does not offer. docker-compose fixed both in #0-16 (`axllent/mailpit` plus the three
`SPRING_MAIL_PROPERTIES_*` overrides). The dev overlay also patched *every* Deployment to 768Mi and 90/120 s probe
delays, Redis included, so any infrastructure Deployment added there inherits JVM settings.

## Decision

- Mailpit is a `Deployment` + `ClusterIP` `Service` named `mailpit` in `k8s/overlays/dev/mailpit.yml`, listed in
  that overlay's `resources`. Never in `k8s/base`: its UI (8025) has no authentication and shows invite and
  password-reset links. No Ingress, NodePort, LoadBalancer or `hostPort` exposes 8025; a developer uses
  `kubectl port-forward`.
- Its image tag equals `docker/docker-compose.yml`'s `axllent/mailpit` tag; the two are bumped together.
- The dev `app-config` patch sets `MAIL_HOST: mailpit`, `MAIL_PORT: "1025"` and adds the three compose overrides
  (`SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH`, `..._STARTTLS_ENABLE`, `..._STARTTLS_REQUIRED`) as `"false"`. Mailpit
  gets no certificate and no SMTP auth.
- Base `MAIL_HOST` is a commented `.local` placeholder ("replace before a real deployment", as #0-26 did for the
  operator address), with `MAIL_PORT: "587"` (submission, STARTTLS + auth, what the services' defaults expect).
  Staging and prod get no relay and no `SPRING_MAIL_PROPERTIES_*` key.
- The dev overlay's blanket Deployment patches (replicas, memory, probe delays) target
  `labelSelector: app.kubernetes.io/component=backend`. A new Deployment in dev that is not one of the services
  carries another `app.kubernetes.io/component` value (Redis: `cache`, Mailpit: `mail`).
- `.github/scripts/check-k8s-mail.sh` enforces the above in `validate-k8s-manifests`: it renders base and the
  three overlays with `kubectl kustomize` and parses the output with Ruby's standard YAML library (the
  mechanism `check-db-password-config.rb` already uses), not grep. `test-k8s-mail.sh` runs it first against a
  copy of the real tree, then against one mutated copy per rule, each of which must fail citing `backlog #0-42`.

## Alternatives considered

- **Mailpit in base, removed by staging/prod patches**: a missed `$patch: delete` ships an unauthenticated inbox
  of invite links into prod; the owner ruled it out.
- **A real relay per overlay** (SES, Mailgun): needs credentials in each overlay's Secret and an external
  service (`ready.md` point 3); no relay exists for this project yet.
- **`yq` or `grep` in the check**: `yq` is not used anywhere in this repository's checks; grep cannot tell a
  Service's selector from a Deployment's labels or a patch's target from its body.
- **Leaving the blanket patches and overriding them for Redis/Mailpit**: every future infrastructure Deployment
  would need its own counter-patch; the label is already on the 7 services.

## Consequences

- Dev on Kubernetes delivers invites and operator emails into Mailpit; staging and prod still send to a host
  that does not resolve until an operator sets `MAIL_HOST` (and SMTP credentials) for a real relay.
- A Renovate bump of the compose `mailpit` tag fails `check-k8s-mail.sh` until the dev manifest is bumped too.
- Violations a reviewer recognises: `mailpit` or `SPRING_MAIL_PROPERTIES_` in base, staging or prod; a dev
  Deployment patch without the `backend` label selector; a non-`ClusterIP` route to 8025; a `latest` tag.
