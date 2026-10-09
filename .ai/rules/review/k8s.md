# Review dimension: k8s (manifests, Dockerfiles, module POMs)

Owned by the maintainer; agents never edit it. Moved from `.claude/agents/k8s-manifest-reviewer.md` on
2026-10-05; the checks are unchanged, each now has an id.

Read by `review-k8s` (the whole file, whenever it runs) and by the implementer: the rules the
architect's plan lists, or the whole file when the change reaches this area (self-check).

You catch the specific CI failures this project's pipeline enforces, before they reach CI. Read
`.ai/context/infrastructure.md` ("CI gotchas") first — it defines exactly what to check here.

If there's no new Dockerfile, no new/changed file under `k8s/`, and no changed `pom.xml` in the diff, say
so and return `APPROVE`.

## Checks, in order of how likely they are to fail CI

- **K8S-01 Dockerfile → Deployment match.** For every directory containing a `Dockerfile`, confirm there
  is a matching `Deployment` in `k8s/base`, name-matched exactly as CI checks it (check an existing
  service's Deployment name against its directory name for the pattern).
- **K8S-02 `service-parent` parenting.** A new runnable service's `pom.xml` has `<parent>` =
  `service-parent`, not the root POM; otherwise `spring-boot-maven-plugin` isn't inherited and
  `java -jar app.jar` fails with "no main manifest attribute". `shared` is the deliberate exception and
  stays on the root parent.
- **K8S-03 Kustomize/kubeconform validity.** If `kubectl kustomize` and `kubeconform` are available, run
  the same check CI runs: `kubectl kustomize k8s/overlays/dev | kubeconform -strict -summary -` (and the
  other overlays). Report any validation errors verbatim. `kubeconform` is often not installed locally;
  if it isn't, say the validation did not run rather than inferring its result.
- **K8S-04 No Spring profile in staging/prod.** Only `k8s/overlays/dev` sets `SPRING_PROFILES_ACTIVE`
  (backlog #0-63).
- **K8S-05 Port and env consistency.** Ports in the Deployment/Service manifests match the service's
  `application.yml` (API and management ports); a known drift once existed for auth-service's management
  port (8097), don't reintroduce one. Every service but auth-service gets `AUTH_SERVICE_URL`.
- **K8S-06 Resource limits and health checks.** A new Deployment has resource requests/limits and
  readiness/liveness probes consistent with the other services' manifests.
- **K8S-07 Docker image matrix.** CI's image matrix is path-filtered: confirm a new service's Dockerfile
  path would be picked up by that filter (check the workflow if it's in the diff, or flag it as something
  to verify manually).
- **K8S-08 POM supply chain.** A changed `pom.xml` that adds a `<plugin>`, `<repository>`,
  `<pluginRepository>` or a dependency is `NEEDS_HUMAN` (`_common.md`): `mvn verify` executes it wherever
  it runs. Version bumps of an existing dependency are not.

## Output notes

Order by which would fail CI first. For each: **What** (file and line), **What breaks** (which CI step
fails and how — quote the expected error where you can), **Suggested fix** (matching the pattern already
used by the other services).

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
