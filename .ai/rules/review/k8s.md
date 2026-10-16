# Review dimension: k8s (manifests, Dockerfiles, module POMs)

Owned by the maintainer; agents never edit it. Moved from `.claude/agents/k8s-manifest-reviewer.md` on
2026-10-05; the checks are unchanged, each now has an id.

Read by `review-k8s` (the whole file, whenever it runs) and by the implementer: the rules the
architect's plan lists, or the whole file when the change reaches this area (self-check).

You catch the specific CI failures this project's pipeline enforces, before they reach CI. Read
`.ai/context/infrastructure.md` ("CI gotchas") first — it defines exactly what to check here.

If there's no new Dockerfile, no new/changed file under `k8s/`, no changed `pom.xml`, no changed root
`.dockerignore` and no changed `Dockerfile.dockerignore` in the diff, say so and return `APPROVE`. The root
`.dockerignore` decides what reaches every image's build context (an allowlist of the service jars since
backlog #0-141), and a `<service>/Dockerfile.dockerignore` replaces it for that Dockerfile's build; check a
change to either against K8S-07, and report one that lets more into the build context as `outOfScope` for
security (SEC-33).

## Checks, in order of how likely they are to fail CI

- **K8S-01 Dockerfile → Deployment match.** For every directory containing a `Dockerfile`, confirm there
  is a matching `Deployment` in `k8s/base`, name-matched exactly as CI checks it (check an existing
  service's Deployment name against its directory name for the pattern).
- **K8S-02 `service-parent` parenting.** A new runnable service's `pom.xml` has `<parent>` =
  `service-parent`, not the root POM; otherwise `spring-boot-maven-plugin` isn't inherited, the jar is not
  repackaged (no `Main-Class`/`Start-Class`, no `BOOT-INF/layers.idx`), and the image's
  `jarmode=tools extract` fails at build time. `shared` is the deliberate exception and stays on the root
  parent.
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
- **K8S-07 Image build in the smoke test.** Since backlog #0-141 (ADR-0030) there is no per-service image
  matrix: the smoke test builds every image through `docker/docker-compose.yml`'s `build:` blocks from
  build-and-test's `service-jars` artifact. A new service needs:
  - a compose service with `build: { context: .., dockerfile: <service>/Dockerfile }`;
  - its name in the smoke test's service lists (`up`, the health checks, the log dump) and in
    build-and-test's `-pl` list;
  - a directory named `<name>-service`, which the artifact's path pattern (`*-service/target/*-service-*.jar`),
    the `.dockerignore` allowlist and `check-no-local-config-in-jars.sh` all match.
- **K8S-08 POM supply chain.** A changed `pom.xml` that adds a `<plugin>`, `<repository>`,
  `<pluginRepository>` or a dependency is `NEEDS_HUMAN` (`_common.md`): `mvn verify` executes it wherever
  it runs. Version bumps of an existing dependency are not. Configuring a plugin the parent already
  manages and the default lifecycle already runs (`maven-jar-plugin`, `maven-surefire-plugin`), without a
  `<version>` of its own or a new `<execution>`, is not adding one: review its configuration instead.
- **K8S-09 A Dockerfile only packages the jar** (backlog #0-141, ADR-0030). Every `<service>/Dockerfile`:
  has a JRE base only (`eclipse-temurin:<version>-jre-alpine`, the same pinned tag in both stages), no JDK;
  runs no `mvnw`/`mvn` and copies no sources: its only input from the context is
  `COPY <service>/target/<service>-*.jar jar/`, with its own service's name;
  keeps the exactly-one-jar guard before `jarmode=tools extract --layers --launcher`;
  copies the four layers in order (dependencies, spring-boot-loader, snapshot-dependencies, application)
  without `--chown`, then `USER appuser`, and starts with
  `org.springframework.boot.loader.launch.JarLauncher`.

## Output notes

Order by which would fail CI first. For each: **What** (file and line), **What breaks** (which CI step
fails and how — quote the expected error where you can), **Suggested fix** (matching the pattern already
used by the other services).

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
