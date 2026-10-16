# ADR-0028: The HPAs own the services' replica counts; overlays size an environment through `minReplicas`/`maxReplicas` (backlog #0-137)

- **Status:** Accepted
- **Backlog:** #0-137 (follow-ups #0-138, #0-139; ceilings bounded by #0-79)
- **Date:** 2026-10-15
- **Author:** human (owner's choice of option and numbers; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

Every service has a HorizontalPodAutoscaler in `k8s/base/<service>/service-hpa.yml` (CPU 70%, memory 80%,
`minReplicas: 1`). Until #0-137 each Deployment also set `spec.replicas: 1`, and the staging and prod overlays sized
the services by patching `/spec/replicas` (prod: incident and ingestion 3, notification, escalation, postmortem and
oncall 2, auth-service nothing, so 1). Two owners wrote one field: the HPA scaled prod's "3" down to 1 within minutes,
every `kubectl apply` reset what the HPA had scaled to, and auth-service, which every service asks for its tenant's
status (#0-82), ran one pod in prod. Kubernetes' documentation ("Migrating Deployments and StatefulSets to horizontal
autoscaling") says to remove `spec.replicas` once an HPA is enabled. No cluster runs these manifests yet, so the
one-time scale-down the documentation warns of costs nothing now.

## Decision

- No Deployment that an HPA targets sets `spec.replicas`, in base or in any overlay patch. Infrastructure without
  an HPA (Postgres, Kafka, Redis, Mailpit) keeps its `replicas`.
- Each overlay sizes the services by patching their HPAs' `minReplicas` and `maxReplicas`:

  | | auth | incident | ingestion | notification, escalation, postmortem | oncall |
  |---|---|---|---|---|---|
  | dev | 1–1 | 1–1 | 1–1 | 1–1 | 1–1 |
  | staging | 1–3 | 2–3 | 2–3 | 1–2 | 1–2 |
  | prod | 2–3 | 3–5 | 3–5 | 2–2 | 2–2 |

- Prod never runs a service on one pod: every prod HPA's floor is at least 2. The floor is for availability; the
  ceiling bounds the Postgres connections (5 per pod, #0-79), and ingestion-service, with no database, is free of it.
- The four services at 2–2 gain availability, not throughput, from a second pod. notification, escalation and
  postmortem consume `incidents.lifecycle` (3 partitions) with listener concurrency 3, so one pod already reads every
  partition; their `@Scheduled` jobs run on one pod at a time under ShedLock; and Slack, mail, SMS and Gemini set
  their pace, not CPU. oncall-service serves HTTP only, to escalation and notification, so its load follows theirs.
  Scaling them is more partitions (with the key-ordering question that raises), not more pods.
- `.github/scripts/check-k8s-environments.rb` (rules 4 and 5, with mutation cases in `test-k8s-environments.sh`)
  fails on any render where a `component=backend` Deployment has no HPA, an HPA targets a Deployment the render does
  not have, the targeted Deployment sets `spec.replicas`, `minReplicas` exceeds `maxReplicas`, or a prod floor is
  below 2.

## Alternatives considered

- **Keep `spec.replicas` and raise `minReplicas` to the same value.** Gives a floor but keeps two owners: a deploy at
  peak still resets an HPA-scaled count to the floor, the "thrashing" the documentation warns of, and the two numbers
  must be kept equal by hand.
- **No HPA, fixed `replicas: 2`, for the services that do not scale with pods** (the four at 2–2). Truer to what
  they do, but two patterns side by side, and which service scales is a measurement nobody has made. An HPA at 2–2
  keeps one pattern; dropping it stays open once the load is measured.
- **Kustomize's `replicas:` field instead of JSON patches.** Another way to write `spec.replicas`; the same two owners.

## Consequences

- Sizing an environment is a change to its HPA patches; a reviewer recognises a violation as `spec.replicas` on a
  service, a service without an HPA, or a prod floor of 1, and CI fails on each.
- Starting a service from 0 needs `kubectl scale --replicas=1`: an HPA is inactive at 0 replicas and `kubectl apply`
  no longer carries a count (`docs/database-roles.md`, the password migration on Kubernetes).
- Raising a ceiling changes #0-79's connection arithmetic (`docs/database-roles.md`); check it there first.
- Left open: nothing protects a floor of 2 from a node drain or from both pods landing on one node (#0-138,
  PodDisruptionBudget and topology spread; since decided in ADR-0029); the HPAs scale the JVMs on memory too, which
  may not follow the load (#0-139). On a cluster that already runs these Deployments, removing `spec.replicas` first
  needs the documentation's Client-Side (`kubectl apply edit-last-applied`) or Server-Side Apply step, or it drops to
  one pod once.
