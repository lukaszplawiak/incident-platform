# ADR-0030: Maven builds the services once; the images only package the jar, in CI the one build-and-test tested (backlog #0-141)

- **Status:** Accepted
- **Backlog:** #0-141 (with #0-100; follow-ups #0-142, #0-143; registry #0-71)
- **Date:** 2026-10-16
- **Author:** human (owner's choice of R1 after a first design, X, was reviewed; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

Until #0-141 every Dockerfile was multi-stage, with Maven inside: it copied the sources and compiled its service,
and resolved its dependencies into a per-service BuildKit cache that does not survive a CI runner. One CI run
therefore compiled the code up to three times:
- build-and-test's `verify`;
- the per-service `docker-build` matrix, up to 7 runners;
- the smoke test's `docker compose up`.

It also downloaded the dependency tree from Maven Central up to 8 times (7 images plus build-and-test).
build-and-test's own cache (setup-java `cache: maven`) restores only an exact match of the POMs' hash, so every
Renovate PR downloaded the whole tree again (actions/setup-java#269). On 2026-10-15 a single 502 from Central
failed the smoke test on three Renovate PRs and `main`. Locally the images downloaded everything a second time,
next to the developer's `~/.m2`. No image is pushed yet (`push: false`; k8s runs `<service>:latest`, #0-71).

## Decision

- **Maven builds, Docker packages.** Each `<service>/Dockerfile` is a JRE image only.
  - The `layers` stage does `COPY <service>/target/<service>-*.jar jar/`. The `RUN` refuses anything but exactly
    one jar.
  - It then runs `java -Djarmode=tools -jar … extract --layers --launcher` (the Dockerfile from Spring Boot's
    reference documentation).
  - Runtime: 4 `COPY`s, from dependencies to application, then `ENTRYPOINT … org.springframework.boot.loader.launch.JarLauncher`.
    Arguments still reach `main` (`break-glass-mfa-reset`, ADR-0007).
  - Files are owned by root and not writable by `appuser`.
  - `.dockerignore` is an allowlist of `*-service/target/*-service-*.jar`.
- **CI: build once, test the image built from it.**
  - build-and-test runs `verify` and uploads the jars as `service-jars` (1 day, `compression-level: 0`).
  - The smoke test (`needs: build-and-test`) downloads them into the workspace and builds and boots the 7 images.
    It is the only job that builds images.
  - The per-service `docker-build` matrix is removed: it built the same images from the same jars, and the smoke
    test also runs them.
  - When a registry exists (#0-71), the image the smoke test built is the one to push.
- **Maven repository cache.** `actions/cache/restore` with `restore-keys` by month, so a POM change downloads only
  what is new; `actions/cache/save` only on a push to `main`. A new month starts empty, which bounds the stale
  versions a restored repository accumulates (#269's concern).
  - `snyk.yml` and `owasp-dependency-check.yml` restore the same entry by the same key and never save.
  - `.mvn/maven.config` retries 429/500/502/503/504.
- **Locally:** `make dev-up` runs `make build` (`./mvnw clean package -DskipTests`, 13 s measured) and then
  `compose up -d --build`.
- **#0-100 is fixed together:** a local jar would otherwise carry `application-local.yml` into a local image.
  - The root POM excludes `application-local.*` and `application-secrets.*` from every jar (`maven-jar-plugin`).
    That includes `shared`, which every service jar nests as `BOOT-INF/lib/shared-*.jar`.
  - CI plants both files in every module, and `check-no-local-config-in-jars.sh` (with its own cases) fails if a
    jar, or its nested `shared` jar, holds one.
- **The smoke test runs the break-glass command once** without options and expects its refusal (exit 1): it
  checks that `JarLauncher` still passes the arguments after the entrypoint to `main` (ADR-0007).

## Alternatives considered

- **X: keep Maven in the Dockerfile and let CI replace a `jar` stage with build-and-test's jar** (built and reviewed
  first). It has two build paths, a contract checker, a compose override and a job building from source on every
  push to `main` and on most Renovate PRs. That job downloads from Central again, so it is the duplicate work this
  ADR removes. It was meant for a fresh clone without a JDK, which this repository does not support anyway: Java 21
  is a README prerequisite.
- **Jib (`jib-maven-plugin`).** Widely used for Java, daemonless, with layers and reproducible digests, and at its
  best when pushing to a registry. But it removes the Dockerfiles, which the service checks
  (`check-tenant-status-config.sh`, the k8s Deployment match), review-k8s and the hardening are written against,
  and it cannot `RUN` (#0-143's CDS). Reconsider with the registry, #0-71.
- **Buildpacks (`spring-boot:build-image`).** The builder images take several GB and the build is slow, against
  the resource budget.
- **The jar from a separate `package -DskipTests` job.** That is a second compilation, and the image jar would not
  be the tested one.
- **Moving `application-local.yml` to `./config/` for #0-100.** It changes every developer's setup (README Step 2,
  Makefile); the exclusion leaves the setup as it is.

## Consequences

- **Per CI run:** one compilation and one resolution (none from Central on a cache hit, only the delta after a
  dependency bump), two runners for the build path instead of up to ten, no Docker layer cache in the Actions
  cache.
- **What the smoke test boots** is exactly what the tests ran against. It waits for the whole build-and-test job,
  not only `verify`: wall clock about 9–10 min instead of 6–7 (estimate, to be measured in the PR).
  - When any step of that job fails, the smoke test does not run, on `main` too: `verify`, "No jar holds a local
    config", and on a pull request the changed-lines coverage gate (`diff-cover`).
  - A pull request below 60% changed-line coverage therefore shows no smoke result until its coverage is fixed.
    That is accepted: it cannot merge as it is anyway. A coverage job of its own would cost a runner and a
    transfer of the JaCoCo reports.
- **A Dockerfile never runs Maven or copies sources.** A reviewer flags a `mvnw`, a `COPY …/src` or a JDK base.
  A new service follows the same shape; the #0-100 exclusion comes from the root POM.
- **The first CI run of a month** (a pull request opened before any push to `main` that month) restores nothing
  and downloads the whole tree from Maven Central once; the retries in `.mvn/maven.config` cover a transient 5xx
  there, and the next push to `main` saves the month's entry.
- **`docker compose up --build` without `make`** packages whatever jar is in `target/`: no jar fails the build,
  and so do two. The README says so.
- **The artifact is public.** `service-jars` can be downloaded from a public repository's runs; it holds what the
  public sources build, and no `application-local` (checked).
- **Left open:** the wrapper's Maven distribution has no checksum (#0-142); CDS/AOT (#0-143); registry push,
  scanning and digests (#0-71, #0-70).
