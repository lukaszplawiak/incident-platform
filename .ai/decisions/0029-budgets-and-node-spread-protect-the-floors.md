# ADR-0029: A PodDisruptionBudget of one pod and a spread over nodes protect every service's replica floor (backlog #0-138)

- **Status:** Accepted
- **Backlog:** #0-138 (follow-up #0-140; builds on ADR-0028, #0-137)
- **Date:** 2026-10-16
- **Author:** human (owner's choice of placement and scope; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

Since ADR-0028 every prod HPA has a floor of at least 2 (auth 2–3, incident and ingestion 3–5, the other four 2–2),
which survives one pod failing. Until #0-138 `k8s/` had no PodDisruptionBudget, so a `kubectl drain` or a managed
cluster upgrade could evict every pod of a service at once, and no `topologySpreadConstraints` or anti-affinity, so
the scheduler could put both pods of a 2–2 service on one node, whose failure takes the service down. Dev runs
every service at 1–1 on minikube (one node); staging runs five services at a floor of 1. No cluster runs these
manifests yet, so neither the node count nor the zones are known. CI renders and validates against Kubernetes 1.36
(ADR-0027).

## Decision

- Each of the seven services has `k8s/base/<service>/pdb.yml`: `policy/v1` PodDisruptionBudget `<service>-pdb`,
  `maxUnavailable: 1`, `unhealthyPodEvictionPolicy: AlwaysAllow`, selector `app: <service>`. It is in base and holds
  for every overlay: one unavailable pod can always be granted, so it never blocks a drain, dev's single pod included.
- Each service's pod template has one `topologySpreadConstraints` entry: `topologyKey: kubernetes.io/hostname`,
  `maxSkew: 1`, `whenUnsatisfiable: ScheduleAnyway`, `labelSelector` `app: <service>`, `matchLabelKeys:
  [pod-template-hash]` (the skew is counted per ReplicaSet, so a rolling update's old pods do not steer the new ones).
- `.github/scripts/check-k8s-environments.rb` rule 6 (mutation cases in `test-k8s-environments.sh`) fails on any render
  where a `component=backend` Deployment is not covered by exactly one budget, a budget sets `minAvailable`, a
  `maxUnavailable` other than 1 or no `AlwaysAllow`, a budget covers no service, or a service has no hostname spread
  with `ScheduleAnyway` selecting its own pods.

## Alternatives considered

- **Budgets in the staging and prod overlays only, for the services with a floor of 2** (the item's first Approach).
  Fourteen resources in two overlays instead of seven in base, and a base that differs from what runs; it buys
  nothing, because `maxUnavailable: 1` is harmless at a floor of 1.
- **`minAvailable` equal to the floor.** At the floor (prod's four 2–2 services always are) it allows no eviction, so
  every drain hangs until someone deletes the budget; and the number must follow every floor change by hand.
- **`whenUnsatisfiable: DoNotSchedule`.** A hard rule leaves pods Pending on a cluster with fewer nodes than pods
  (minikube; a prod node pool of 2 with a service at 3–5), which turns a placement preference into an outage.
- **Preferred `podAntiAffinity`.** The older mechanism: it only scores "not with another pod of the service", so past
  two nodes it does not even out 3–5 pods, and the Kubernetes docs point to topology spread for this.
- **A zone constraint (`topology.kubernetes.io/zone`) now.** Untestable without a cluster whose zones are known;
  left to #0-140.

## Consequences

- A drain moves a service one pod at a time: the next eviction waits until the replacement is Ready. A reviewer
  recognises a violation as a service without `pdb.yml`, a `minAvailable`, a hard spread or none, and CI fails on each.
- A budget limits voluntary evictions only (drain, upgrade, the eviction API), not `kubectl delete`, `kubectl scale`
  (the password-migration runbook in `docs/database-roles.md` is unaffected), a node failing or the HPA scaling down.
- The spread is applied when a pod is scheduled. An HPA scale-down or a drain can leave pods unevenly placed until
  they are rescheduled (a descheduler would rebalance; not deployed). `ScheduleAnyway` is a preference: on a cluster
  with fewer nodes than pods, pods still share nodes.
- Left open: spreading over zones, and whether to rebalance after scale-down, both in #0-140.
