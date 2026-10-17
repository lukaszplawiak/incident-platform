# ADR-0031: The Maven distribution `./mvnw` runs is pinned by SHA-256, checked against Apache's SHA-512, and cached in CI by its exact key (backlog #0-142)

- **Status:** Accepted (2026-10-17). Amends ADR-0030: its Maven repository cache no longer holds `~/.m2/wrapper`, and its key no
  longer hashes `.mvn/wrapper/maven-wrapper.properties`
- **Backlog:** #0-142 (follows #0-141 / ADR-0030)
- **Date:** 2026-10-17
- **Author:** human (owner chose option B; written up by Claude Code in the owner's session)
- **Reversible:** yes

## Context

`.mvn/wrapper/maven-wrapper.properties` named Maven 3.9.16 by URL only; `./mvnw` (wrapper 3.3.4,
`only-script`) ran whatever it downloaded. Since #0-141 CI kept the unpacked distribution (`~/.m2/wrapper`) in the
monthly Maven repository cache, restored by prefix (`restore-keys`) in ci.yml and in `snyk.yml` and
`owasp-dependency-check.yml`, whose jobs hold scanner tokens. Every other tool CI downloads is pinned by checksum
(ADR-0021, ADR-0027). Verified on 2026-10-17:

- `mvnw` checks `distributionSha256Sum` **only when it downloads** (`mvnw:225-250`, `mvnw.cmd:138-145`). If
  `~/.m2/wrapper/dists/apache-maven-<v>/<hash>` exists, it runs it with no check (`mvnw:153-156`), and that
  directory is named after the URL alone (`mvnw:146`). A wrong sum with an existing directory: exit 0.
- `mvnw` reads the file with `IFS="=" read`. The key is untrimmed (` distributionSha256Sum=` is not read),
  and `read` drops a last line that has no newline, so a sum on that line is silently not checked.
- Maven Central publishes no `.sha256` for the zip (404), only `.sha1`, `.sha512` and `.asc`.
- Renovate's maven-wrapper manager (active here: e4f2099) rewrites `distributionSha256Sum` on a bump by hashing
  what it downloaded (`hashStream(http.stream(url))`). It compares that hash with nothing.
- Six services (escalation, incident, ingestion, notification, oncall, postmortem) still carried their own
  `mvnw`, `mvnw.cmd` and `.mvn/wrapper/maven-wrapper.properties` from the first commit (c029c7a), with the same
  URL. Renovate kept bumping them, nothing ran them, and they had no sum. Because the directory is named after
  the URL, running one of them first would have unpacked an unchecked Maven that the root `./mvnw` then runs.

## Decision

- `maven-wrapper.properties` carries `distributionSha256Sum`. The first value was computed from the zip on
  `repo.maven.apache.org`, after that zip matched the `.sha512` on `downloads.apache.org`.
- `.github/scripts/check-maven-wrapper-sum.sh` (cases in `test-maven-wrapper-sum.sh`) reads the file with
  mvnw's own loop. It requires exactly one `distributionUrl`, which must be Central's
  `apache-maven-<v>-bin.zip`, and exactly one lowercase 64-hex `distributionSha256Sum`. It downloads the zip,
  checks it against Apache's `.sha512` (on `downloads.apache.org`, or `archive.apache.org` as a fallback), and
  then compares its SHA-256 with the property.
- In CI, `~/.m2/wrapper` has its own cache, `maven-wrapper-<os>-<hashFiles(properties, checker)>`: an exact key,
  with no month and no `restore-keys`. build-and-test restores it and runs the checker on a miss, which is when
  mvnw is about to download. It saves the cache on a push to `main` only. snyk/owasp restore it by the same key,
  never save it and never run the checker. The Maven repository cache no longer holds `~/.m2/wrapper`, and its key
  hashes the POMs only, under a new prefix `maven-repo-<os>-<month>-`: entries saved before, which hold an
  unchecked `~/.m2/wrapper`, cannot match its `restore-keys` (their other path list already keeps `actions/cache`
  from restoring them, `getCacheVersion`; the prefix does not rely on that).
- The root wrapper is the only one. The six module copies are deleted, together with the modules' `.gitattributes`,
  which only set line endings for them. Every Maven command runs from the root (`-pl <module>`).

## Alternatives considered

- **The sum alone** (the backlog's first Approach): `restore-keys` would still bring back an unpacked
  distribution that mvnw never checks, and a Renovate bump's sum would stay trust-on-first-use.
- **No cache for `~/.m2/wrapper`**: every job downloads and checks. But mvnw's `curl` has no retry, so one
  Central 5xx fails the job again, which is the #0-141 incident.
- **Checking the cached directory itself**: there is nothing to check it against. Apache signs the zip, and mvnw
  deletes the zip after unpacking.
- **GPG (`.asc` + Apache `KEYS`)** instead of `.sha512`: stronger against a compromised mirror, but it needs
  `gpg` and a pinned `KEYS` file whose own trust is the same question. `.sha512` from a host other than the zip's
  answers the threat this item is about: a bad or substituted download.

## Consequences

- A Maven bump PR (Renovate's or by hand) misses the cache and runs the checker: a sum that is not Apache's fails
  build-and-test. A hand bump computes the sum from a zip already checked against `.sha512`, as in the first bullet
  of the Decision.
- A cache hit is trusted as far as `main`'s build is: the entry is saved at the end of build-and-test, after
  `verify` ran `main`'s code and tests, not right after the checked download (the repository cache works the same
  way, and only merged code runs there).
- The four copies of the wrapper key (ci.yml, snyk.yml, owasp's two jobs) stay equal (the comments say so; a drift
  is only a miss, after which mvnw downloads and checks the zip itself; nothing checks it, backlog #0-145); a reviewer flags a `~/.m2/wrapper` back in
  the repository cache's `path`, a `restore-keys` on the wrapper cache, a mvnd/other-host `distributionUrl`, or a
  `mvnw` or `.mvn/` anywhere but the root.
- Knowingly left open: a developer's existing `~/.m2/wrapper` (and the devcontainer's `incident-platform-m2`
  volume) is not re-checked. Deleting `~/.m2/wrapper/dists/apache-maven-<v>` once makes mvnw download and check
  it (README). There is no GPG check (above). `mvnw` itself is a tracked file, reviewed like code.
