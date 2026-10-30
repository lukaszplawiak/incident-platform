# Review dimension: security

Owned by the maintainer; agents never edit it. Moved from `.claude/agents/security-reviewer.md` and the
tenant-isolation section of `.claude/agents/code-reviewer.md` on 2026-10-05, each check with an id; rewritten as
invariants on 2026-10-26 (backlog #0-115): each rule says what is true of the code, and its "Check:" line what to look
for in a diff. The ids did not change; SEC-32 is retired into SEC-30.

Read by `review-security` (the whole file, for every change) and by the implementer, which reads this file in
full for every item; the architect's plan only adds emphasis to the rules at risk. For the implementer a rule is what
the code must be; for the reviewer, its "Check:" line is where a violation shows. When the diff touches a path
of the Owner-only list of `.ai/rules/protected-paths.md` (e.g. the AI factory, CI, the devcontainer, the rules
themselves), `review-security` also reads `factory.md` and judges that part by its `FAC-S` rules (backlog
#0-161).

**No rule is dropped for being general knowledge** (unlike `performance.md`): a blocking finding must cite a rule
(`_common.md`), so a class of vulnerability without one could be reported but never blocked (#0-136 was that case).
`_common.md` defines the two labels ("Enforced by", "Backed by").

Think offensively: for each piece of new or changed code, ask "how would I break this?" rather than "does
this look reasonable?" Think as an attacker with an account in another tenant, or with a lower role in
the same tenant. Read `.ai/context/security.md` first — it defines the multi-tenancy model and the shared
security infrastructure this review depends on — and the ADRs the change touches
(`.ai/decisions/README.md`, area column).

Order of severity: tenant boundary and authentication bypass first. For a blocking finding, `impact` is
the attack scenario: a specific, concrete way this could be exploited or triggered.

## 1. Multi-tenant data exposure — the most damaging class of bug here

- **SEC-01 Nothing crosses a tenant boundary.** No response, log line, cache entry, event or file holds one tenant's
  data where another tenant can read it; a cache key carries the tenant. Check: every new query, Kafka consumer, cache
  and log statement, and what tenant it reads under.
- **SEC-02 The request's tenant is read where it is still set.** On a request, `TenantContext` is set by the
  authentication filter, which also stores it as the request attribute `TenantContext.REQUEST_ATTRIBUTE_TENANT_ID`;
  code that runs after the ThreadLocal is cleared (an observation, an outer filter) reads the attribute. Check: a new
  filter, handler or callback that reads `TenantContext` outside the request's main path.
- **SEC-03 A tenant comes from authentication.** It is read from the authenticated principal (or a signed token) and
  travels in the data; never from a request body, a path or query parameter, or an unsigned header. Check: every new
  read of a tenant id traces to `TenantContext`, the principal or a signed token.
- **SEC-04 A Kafka record is handled under its own tenant.** The tenant is resolved per record with
  `TenantKafkaRecordResolver`, never once for a batch; `TenantContext` is cleared in a `finally`; a record with a
  missing or malformed tenant goes to the dead-letter topic, never on unscoped; every record sent is built by
  `TenantRecords.forTenant`. Check: a new or changed listener, its loop, its `finally`, and every `ProducerRecord`.
- **SEC-05 Async work keeps its tenant.** It runs on an executor decorated with `TenantAwareTaskDecorator`. Check: a
  new `@Async`, executor, `CompletableFuture` or scheduled hand-off, and which executor it uses. (Bounding the
  executor is PERF-21's.)
- **SEC-06 Every query is tenant-scoped.** A `tenant_id` predicate, or a repository method that carries the tenant,
  in derived, JPQL and native queries and in bulk `@Modifying` statements alike. Check: every new or changed query
  and repository method, and its `WHERE`.
- **SEC-07 Tenant content reaches only the tenant's members.** An incident's title, id or severity goes to members of
  that tenant only: no shared address, no platform-wide channel, no fallback destination, no other team without a
  reason. Check: a new recipient, channel or fallback in a notification path.
- **SEC-08 A suspended tenant cannot write or sign in.** A new write path open to a `UserPrincipal` goes through
  `TenantStatusFilter` (or is listed in `tenant-status.read-only.allowed-writes` on purpose), and a new sign-in or
  join path calls `TenantAccessService.requireCanSignIn` / `requireCanJoin`. Backed by: `check-tenant-status-config.sh`
  (every service can ask for a tenant's status). Check: a new write endpoint or sign-in path, which the check does
  not see.

## 2. Authentication & authorization

- **SEC-10 Every endpoint states who may call it.** A new endpoint has an explicit requirement: `@PreAuthorize`, or a
  filter-chain rule that names its path and role (the shared chain's catch-all `authenticatedExceptPurposeTokens()`
  admits every role, so it is not one); nothing is added to `PUBLIC_PATHS` without a reason in the PR. Check: a new
  mapping without `@PreAuthorize` or such a rule, a removed one, or a new `PUBLIC_PATHS` entry.
- **SEC-11 A role is written in the form its reader expects.** `@PreAuthorize` and filter-chain rules use the
  non-prefixed `hasRole("ADMIN")` / `SecurityRoles.*_NAME`; JWT claims and `UserPrincipal.hasRole` the `ROLE_`
  prefix. A mismatch fails silently, open or closed. Check: a new role string and where it is compared.
- **SEC-12 A forked `SecurityFilterChain` stays as strict as the shared one.** A service that declares its own chain
  (instead of extending the shared one) keeps CORS and `PUBLIC_PATHS`, adds `ApiKeyAuthFilter` itself, and ends in
  `authenticatedExceptPurposeTokens()`, not `authenticated()`; the most common way auth gets weakened here. Backed by:
  `AuthenticatedExceptPurposeTokensTest` (the helper itself, not that a chain uses it). Check: a new or changed
  `SecurityFilterChain` bean.
- **SEC-13 Access by id checks the owner.** Reading or changing a resource by id checks its tenant, team or role, not
  only that the row exists (IDOR). Check: a new `findById` or path id behind an endpoint, and what it compares.
- **SEC-14 An API key reaches only the routes its scope allows.** A new auth-service route open to keys appears in
  `SecurityConfig` with its scope (`ApiKeyAccess`); an endpoint open to `ROLE_SERVICE` never dereferences a `null`
  `@AuthenticationPrincipal UserPrincipal`. Backed by: `ApiKeyDenyByDefaultTest` (routes not listed are refused).
  Check: a new route meant for keys or services, and its principal handling.
- **SEC-15 A service-to-service call carries the target's token and fails safely.** The token is minted for the
  target (`ServiceTokenProvider.getToken(tenantId, ServiceNames.<TARGET>)`), the client has a connect and a read
  timeout, and a fail-open fallback calls `ClientFallbackMetrics.record`. Check: a new client or call between services.
- **SEC-16 A cross-tenant action is the platform operator's, decided and audited.** It needs the `PlatformAccess`
  rule, an audit event in the operator tenant and a backlog decision (`.ai/context/security.md`, ADR-0005/0006).
  Check: a new path that reads or writes another tenant's data.

## 3. Injection & input validation

- **SEC-20 A query is parameterized.** SQL and JPQL take values as parameters (Spring Data methods, `@Query` with
  `:params`), never by string concatenation. Check: a query string built with `+`, `format` or a builder from input.
- **SEC-21 Input is validated and bounded before it is used.** What reaches a query, a file path, a log line or an
  outbound HTTP or Kafka call has a checked type and size; an outbound call to a URL a user or tenant can configure is
  an SSRF candidate, and what restricts its destination is named. Check: a new input that reaches one of those, and
  its checks.
- **SEC-22 Untrusted input is deserialized into a fixed type.** Kafka payloads and request bodies are parsed into a
  tree or a known type, never with polymorphic or default typing. Check: a new deserializer, a Jackson typing setting,
  or a payload mapped to `Object` / an interface.
- **SEC-23 A webhook or callback is verified.** Its signature (or token) is checked before it is acted on. Check: a new
  endpoint called by a third party.

## 4. Secrets & credentials

- **SEC-30 No secret is written into the repository.** Code, config, test fixtures, committed YAML, `.ai/work/` files
  and workflow files hold no secret, API key or credential; a secret (`jwt.secret`, `mfa.encryption-key`,
  `slack.encryption-key`, `gemini.api-key` and any new one) is read from configuration or the environment, never a
  literal (`application-local.yml`, gitignored, holds a developer's values). Backed by:
  `secret-scan.sh` (betterleaks on a PR's commits; known secret shapes only) and `check-packaged-profiles.sh` (in a
  packaged `application.*`, a key ending in `secret`, `encryption-key`, `private-key` or `api-key` is a bare `${VAR}`;
  moved here from `architecture.md`'s section 4, review round 1 of its rewrite). Check: a new literal that is a
  credential in a shape the scan does not know or under a key name the check does not match, or a secret given a
  default.
- **SEC-31 No secret or raw payload is logged or returned.** A token, secret, password, key or raw payload reaches no
  log line (at any level), exception message, audit metadata, dead-letter reason or anything a client can read; a
  provider's text goes to the log only, never to a tenant-readable record (#0-93). Check: a new log statement,
  exception message or reason built from a credential, a request, a payload, or a message the platform did not
  write: a database, driver, parser or client exception's, or one wrapped in the platform's own exception, quotes
  what its query, call or input held. Such a line is the finding whatever it carries today: the next change that
  binds a secret there turns it into a leak no review of that change looks for. The platform's own content-free
  messages are kept; any other exception is named by its type and frame, as `KafkaFailures.reason` and `describe`
  do (#0-96, backlog #0-187). Not this rule's: the throwable passed to a logger, whose message reaches
  `error.message` and `error.stack_trace` as the library wrote it (README "Infrastructure Hardening", known gap,
  backlog #0-188), unless the change itself puts a credential or payload into what that exception quotes (a URL or
  query a client names in its I/O error, a header it echoes).
- **SEC-32** retired — merged into SEC-30 (the named secrets are read from configuration), 2026-10-26.
- **SEC-33 A gitignored secret file never reaches a jar or an image** (backlog #0-100, #0-141, ADR-0030).
  `.gitignore` keeps a developer's secret files out of git, not out of a build: Maven packages whatever is
  in `src/main/resources`, and a jar built on a developer's machine is what the local images package. So:
  every file name `.gitignore` lists as holding secrets that can sit under `src/main/resources`
  (`application-local.*`, `application-secrets.*`) is also in the root POM's `maven-jar-plugin`
  `<excludes>`, planted by build-and-test's "Plant a local config the jars must leave out" step and
  matched by `check-no-local-config-in-jars.sh`. A new pattern in `.gitignore` without the other two is a
  finding;
  the exclusion stays in the root POM, so `shared` (nested in every service jar as
  `BOOT-INF/lib/shared-*.jar`) is covered. Moving it to `service-parent`, or a module that overrides the
  jar plugin's `<excludes>` without repeating them, is a finding;
  `.dockerignore` stays an allowlist of `*-service/target/*-service-*.jar`. An entry that lets sources,
  `.env` files or a resources directory into the build context is `NEEDS_HUMAN`;
  the `service-jars` artifact is public (a public repository's runs): a change that uploads anything
  beyond those jars, or uploads them before the "No jar holds a local config" step, is a finding.
  Backed by: `check-no-local-config-in-jars.sh` (the two known patterns). Check: a new secret-file pattern in
  `.gitignore`, a change to the jar plugin's `<excludes>`, `.dockerignore` or the artifact upload.

## 5. Cryptography & tokens

- **SEC-40 A token is issued and checked in full.** Its expiry is set and enforced, its algorithm is not weakened,
  its signature is always verified and its `aud` checked. Check: new or changed token generation or validation.
- **SEC-41 A credential goes through the existing hashing.** Passwords and credentials use the existing hashing setup,
  never a new scheme; an emailed credential is created when sent and never stored raw. Check: a new place that stores,
  compares or sends a credential.

## 6. Dependency risk

- **SEC-50 A dependency change is reviewed with its suppressions.** A new or bumped dependency is checked against
  `owasp-suppressions.xml` / `.snyk`: a suppression it makes stale is removed, one it needs is added with a reason. A
  new dependency is `NEEDS_HUMAN` (`_common.md`). Backed by: Snyk and the OWASP check (known CVEs; Snyk fails on every
  run until #0-69). Check: a change to a POM's dependencies or versions, and the suppression files.

## Output notes

For each finding: **What** (the concrete vulnerability, file and line), **Attack scenario** (the
`impact`), **Suggested fix** (concrete and minimal — the smallest change that closes the gap, following
existing patterns in this codebase rather than introducing a new security mechanism). If a category has
nothing to flag, say so briefly rather than omitting it — an empty tenant-isolation section reads very
differently from a skipped one.

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
