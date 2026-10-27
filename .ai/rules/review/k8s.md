# Review dimension: k8s (manifests, Dockerfiles, module POMs)

Owned by the maintainer; agents never edit it. Moved from `.claude/agents/k8s-manifest-reviewer.md` on
2026-10-05, each check with an id; rewritten as invariants on 2026-10-27 (backlog #0-115): each rule says what is
true of the manifests, images and POMs, and its "Check:" line what to look for in a diff. No id changed its meaning.

Read by `review-k8s` (the whole file, whenever it runs) and by the implementer: the rules the
architect's plan lists, or the whole file when the change reaches this area (self-check). For the implementer a rule
is what the change must keep true; for the reviewer, its "Check:" line is where a violation shows. `_common.md`
defines the two labels ("Enforced by", "Backed by").

Read `.ai/context/infrastructure.md` ("CI gotchas") first: it describes the checks named below.

If there's no new Dockerfile, no new/changed file under `k8s/`, no changed `pom.xml`, no changed root
`.dockerignore` and no changed `Dockerfile.dockerignore` in the diff, say so and return `APPROVE`. The root
`.dockerignore` decides what reaches every image's build context (an allowlist of the service jars since
backlog #0-141), and a `<service>/Dockerfile.dockerignore` replaces it for that Dockerfile's build; check a
change to either against K8S-07, and report one that lets more into the build context as `outOfScope` for
security (SEC-33).

## Rules, in order of how likely they are to fail CI or a deployment

- **K8S-01 Every service with a Dockerfile has a Deployment in `k8s/base`.** The Deployment's name is the
  service's directory name (check an existing service's pair for the pattern). Backed by: validate-k8s-manifests'
  "Cross-check every service has a Deployment in base", which greps the base render for `name: <service>`, so a
  `Service` or a `PodDisruptionBudget` of that name passes it without a Deployment. Check: for a new Dockerfile, a
  resource of `kind: Deployment` with that exact name.
- **K8S-02 A runnable service's POM has `service-parent` as its parent.** Not the root POM: otherwise
  `spring-boot-maven-plugin` isn't inherited, the jar is not repackaged (no `Main-Class`/`Start-Class`, no
  `BOOT-INF/layers.idx`), and the image's `jarmode=tools extract` fails at build time. `shared` is the deliberate
  exception and stays on the root parent. Backed by: the Docker Compose smoke test, which builds every image; it
  runs on a pull request only when a service's code or the infrastructure changed. Check: the `<parent>` of a new
  or changed service `pom.xml`.
- **K8S-03 The manifests render and validate.** `kubectl kustomize` renders base and the three overlays, and
  `kubeconform -strict` accepts each render against the Kubernetes version and schemas pinned in `ci.yml` (backlog
  #0-76, ADR-0027). Enforced by: validate-k8s-manifests (the render and validate steps). A local run without the
  pins in CLAUDE.md's command validates against other schemas and proves nothing.
- **K8S-04 Staging and prod start with no Spring profile.** Only `k8s/overlays/dev` sets a profile: the dev profile
  enables incident-service's unauthenticated `/dev/token` (backlog #0-63). Backed by: validate-k8s-manifests'
  "Staging and prod set no Spring profile", a regex on the dotted, underscored or dashed key
  (`spring.profiles.active`, `SPRING_PROFILES_ACTIVE`); a profile set in nested YAML (a `spring:` / `profiles:` /
  `active:` block in a ConfigMap) or through another variable passes it. Check: any profile setting the diff
  brings into base, staging or prod, in whatever form.
- **K8S-05 A Deployment agrees with its service's configuration.** Its container, Service and probe ports are the
  service's API and management ports from `application.yml` (a drift once existed for auth-service's management
  port, 8097), and every service but auth-service gets `AUTH_SERVICE_URL`. Backed by: `check-tenant-status-config.sh`
  (CI's eighth structural rule), which checks only that `application.yml` reads `${AUTH_SERVICE_URL}`, not that a
  manifest sets it; nothing checks the ports. Check: each port and env entry the diff adds or changes, against the
  service's `application.yml`.
- **K8S-06 Every service's Deployment has resource requests and limits and both probes.** `resources.requests`,
  `resources.limits`, a `readinessProbe` and a `livenessProbe`, consistent with the other services' manifests; a
  change never drops one from an existing Deployment. Check: every container spec the diff adds or changes, and
  every `resources:` or `*Probe:` line it removes.
- **K8S-07 Every service is built and started by the smoke test.** Since backlog #0-141 (ADR-0030) there is no
  per-service image matrix: the smoke test builds every image through `docker/docker-compose.yml`'s `build:`
  blocks from build-and-test's `service-jars` artifact. A service needs:
  - a compose service with `build: { context: .., dockerfile: <service>/Dockerfile }`;
  - its name in the smoke test's service lists (`up`, the health checks, the log dump) and in
    build-and-test's `-pl` list;
  - a directory named `<name>-service`, which the artifact's path pattern (`*-service/target/*-service-*.jar`),
    the `.dockerignore` allowlist and `check-no-local-config-in-jars.sh` all match.
  Backed by: the smoke test, which fails when a service in compose does not start; a service left out of a list
  (the health checks, the log dump) passes it unseen. Check: a new service against each list above.
- **K8S-08 A POM adds no plugin, repository or dependency without the owner.** A changed `pom.xml` that adds a
  `<plugin>`, `<repository>`, `<pluginRepository>` or a dependency is `NEEDS_HUMAN` (`_common.md`): `mvn verify`
  executes it wherever it runs. A version bump of an existing dependency is not. Configuring a plugin the parent
  already manages and the default lifecycle already runs (`maven-jar-plugin`, `maven-surefire-plugin`), without a
  `<version>` of its own or a new `<execution>`, is not adding one: review its configuration instead. Backed by:
  `check-factory-guards.sh` (a POM that adds one of these elements); its step passes when the owner wrote the PR
  or approved its commit. Check: every added `<plugin>`, `<repository>`, `<pluginRepository>` and `<dependency>`,
  in any `pom.xml`.
- **K8S-09 A Dockerfile only packages the jar** (backlog #0-141, ADR-0030). Every `<service>/Dockerfile`:
  - has a JRE base only (`eclipse-temurin:<version>-jre-alpine`, the same pinned tag in both stages), no JDK;
  - runs no `mvnw`/`mvn` and copies no sources: its only input from the context is
    `COPY <service>/target/<service>-*.jar jar/`, with its own service's name;
  - keeps the exactly-one-jar guard before `jarmode=tools extract --layers --launcher`;
  - copies the four layers in order (dependencies, spring-boot-loader, snapshot-dependencies, application)
    without `--chown`, then `USER appuser`, and starts with `org.springframework.boot.loader.launch.JarLauncher`.
  Backed by: the `.dockerignore` allowlist and the smoke test's build, which fail on a source or a `mvnw` the
  context does not hold; a JDK base, a `--chown`, a missing `USER` or a dropped guard build fine. Check: every line
  of a new or changed Dockerfile against the list.
- **K8S-10 Surefire keeps the JaCoCo agent.** Every `<argLine>` of the surefire (or failsafe) configuration in any
  POM starts with `@{argLine}`, and no module sets `argLine` without it: `jacoco:prepare-agent` puts its
  `-javaagent` flag in that property, an explicit `<argLine>` replaces it, and without the agent both coverage
  checks (`jacoco:check` and the changed-lines gate) skip silently (backlog #0-57). `${argLine}` is resolved too
  early and does not count. Check: every `<argLine>` and every `argLine` property the diff adds or changes.

## Rules a check decides

Each is decided in full by its check in the required validate-k8s-manifests job, on the renders of base and the
three overlays, so it has no "Check:" line and is not reviewed by hand (`_common.md`). A check that stops deciding
one in full turns its label into "Backed by", and the rule gets a "Check:" line.

- **K8S-20 Each overlay deploys into its own namespace.** Enforced by: `check-k8s-environments.rb` (rule 1).
- **K8S-21 Prod runs released images.** No untagged or `latest` image, no service tagged with an environment's
  name. Enforced by: `check-k8s-environments.rb` (rules 2 and 3).
- **K8S-22 The HPAs own the replica counts.** Each service Deployment has an HPA aimed at it and no `spec.replicas`,
  `minReplicas` is not above `maxReplicas`, and prod's floor is at least 2 (backlog #0-137, ADR-0028). Enforced by:
  `check-k8s-environments.rb` (rules 4 and 5).
- **K8S-23 A budget and a spread protect each service's floor.** Exactly one PodDisruptionBudget per service, with
  `maxUnavailable: 1` and `unhealthyPodEvictionPolicy: AlwaysAllow`, and a spread over `kubernetes.io/hostname`
  with `ScheduleAnyway` (backlog #0-138, ADR-0029). Enforced by: `check-k8s-environments.rb` (rule 6).
- **K8S-24 Mail is caught in dev only.** Mailpit and the `SPRING_MAIL_PROPERTIES_*` overrides exist only in the dev
  overlay, never exposed beyond the cluster, with compose's image tag (backlog #0-42, ADR-0026). Enforced by:
  `check-k8s-mail.rb`.

## Output notes

Order by which would fail CI or a deployment first. For each: **What** (file and line), **What breaks** (which CI
step fails and how — quote the expected error where you can — or what the deployment does wrong), **Suggested
fix** (matching the pattern already used by the other services).

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
