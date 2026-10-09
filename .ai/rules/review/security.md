# Review dimension: security

Owned by the maintainer; agents never edit it. Moved from `.claude/agents/security-reviewer.md` and the
tenant-isolation section of `.claude/agents/code-reviewer.md` on 2026-10-05; the checks are unchanged,
each now has an id so a blocking finding can cite it.

Read by `review-security` (the whole file, for every change) and by the implementer, which reads this
file in full for every item; the architect's plan only adds emphasis to the rules at risk.

Think offensively: for each piece of new or changed code, ask "how would I break this?" rather than "does
this look reasonable?" Think as an attacker with an account in another tenant, or with a lower role in
the same tenant. Read `.ai/context/security.md` first — it defines the multi-tenancy model and the shared
security infrastructure this review depends on — and the ADRs the change touches
(`.ai/decisions/README.md`, area column).

Order of severity: tenant boundary and authentication bypass first. For a blocking finding, `impact` is
the attack scenario: a specific, concrete way this could be exploited or triggered.

## 1. Multi-tenant data exposure — the most damaging class of bug here

- **SEC-01** Could this code path return, log, or leak data across tenant boundaries? Check every new
  query, every new Kafka consumer, every new cache key (a cache key without the tenant is a leak).
- **SEC-02** HTTP: does `TenantContext` get set correctly, and is it read from the request attribute
  (`TenantContext.REQUEST_ATTRIBUTE_TENANT_ID`) where the ThreadLocal would already be cleared?
- **SEC-03** Is a tenant ever taken from a request body, a path parameter or an unsigned header instead of
  the authenticated principal? (A tenant comes from authentication and travels in the data.)
- **SEC-04** Kafka: is the tenant resolved **per record** via `TenantKafkaRecordResolver` (not from a
  batch), is `TenantContext` cleared in a `finally` block, and could a malformed or missing tenant header
  let a record through unscoped instead of going to the dead-letter topic? Is every record built through
  `TenantRecords.forTenant`?
- **SEC-05** Async work: does it run under `TenantAwareTaskDecorator`, or could it silently run without
  tenant context on a plain executor?
- **SEC-06** Are queries actually tenant-scoped (a `tenant_id` predicate, or a repository method that
  carries it), including native queries and bulk `@Modifying` statements?
- **SEC-07** Notifications: can tenant content (incident title, id, severity) reach anyone who is not a
  member of that tenant (a shared address, a platform-wide channel, another team without a reason)?
- **SEC-08** Tenant status: does a new write path open to a `UserPrincipal` respect suspension
  (`TenantStatusFilter`, `tenant-status.read-only.allowed-writes`), and does a new sign-in path call
  `TenantAccessService.requireCanSignIn` / `requireCanJoin`?

## 2. Authentication & authorization

- **SEC-10** Does every new endpoint have an explicit auth requirement — no accidental additions to
  `PUBLIC_PATHS`, no missing `@PreAuthorize`?
- **SEC-11** Do role checks use the correct constant form (non-prefixed `hasRole("ADMIN")` /
  `SecurityRoles.*_NAME` for `@PreAuthorize` and filter-chain rules, `ROLE_`-prefixed for JWT claims and
  `UserPrincipal.hasRole`)? A mismatch silently fails open or closed.
- **SEC-12** Does a service declaring its own `SecurityFilterChain` (instead of extending the shared one)
  accidentally widen what's public, drop `ApiKeyAuthFilter`, or end in `authenticated()` instead of
  `authenticatedExceptPurposeTokens()`? This is the single most common way auth gets weakened in this
  codebase.
- **SEC-13** IDOR: does access to a resource by id check its owner (tenant, team, role), not only that the
  row exists?
- **SEC-14** API keys: does a new auth-service route open to keys appear in `SecurityConfig` with its scope
  (`ApiKeyAccess`)? Does a new endpoint open to `ROLE_SERVICE` avoid dereferencing a `null`
  `@AuthenticationPrincipal UserPrincipal`?
- **SEC-15** Service-to-service: is the token minted for the **target** (`ServiceNames.<TARGET>`), does
  the client have a connect/read timeout, and does a fail-open fallback call `ClientFallbackMetrics.record`?
- **SEC-16** Platform operator capabilities: a new cross-tenant action needs the `PlatformAccess` rule, an
  audit event in the operator tenant and a backlog decision (`.ai/context/security.md`, ADR-0005/0006).

## 3. Injection & input validation

- **SEC-20** SQL/JPQL built by string concatenation instead of parameterized queries or Spring Data
  method/derived queries.
- **SEC-21** Unvalidated or unbounded input reaching a query, a file path, a log line, or an outbound
  HTTP/Kafka call. An outbound HTTP call to a URL a user or tenant can configure is an SSRF candidate.
- **SEC-22** Deserialization of untrusted input without type restriction (Kafka payloads included).
- **SEC-23** A webhook or callback accepted without verifying its signature.

## 4. Secrets & credentials

- **SEC-30** Hardcoded secrets, API keys, or credentials in code, config, or test fixtures (not just
  `application-local.yml`, which is gitignored, but also test resources, committed YAML, `.ai/work/` files
  and workflow files).
- **SEC-31** Secrets or tokens logged, even at DEBUG level, or included in exception messages, audit
  metadata, dead-letter reasons or anything that could reach a client.
- **SEC-32** New use of `jwt.secret`, `mfa.encryption-key`, `slack.encryption-key` or `gemini.api-key`:
  confirm it's read from config/environment, never a literal.

## 5. Cryptography & tokens

- **SEC-40** New or changed token generation/validation logic: correct expiry handling, no weakened
  algorithm, no skipped signature verification, `aud` checked.
- **SEC-41** Password/credential handling: confirm it goes through the existing hashing setup, not a new
  one-off scheme; emailed credentials are created when sent and never stored raw.

## 6. Dependency risk

- **SEC-50** If the change adds or bumps a dependency, note whether it's the kind of change that should
  also update `owasp-suppressions.xml` / `.snyk`, or whether a suppression there looks stale relative to
  this change. A new dependency is `NEEDS_HUMAN` (`_common.md`).

## Output notes

For each finding: **What** (the concrete vulnerability, file and line), **Attack scenario** (the
`impact`), **Suggested fix** (concrete and minimal — the smallest change that closes the gap, following
existing patterns in this codebase rather than introducing a new security mechanism). If a category has
nothing to flag, say so briefly rather than omitting it — an empty tenant-isolation section reads very
differently from a skipped one.

## Calibration

Added only by `/apply-audit` from accepted audit recommendations. Empty until the first audit.
