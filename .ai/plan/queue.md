# Execution queue — 2026-11-02

<!-- queue:start -->
| # | Item | Why here |
|---|---|---|
| 1 | #0-199 | settles what the route walks of #0-196/#0-198/#0-197 expect for OPTIONS, and its AC13 rewrites their text; Risk high: owner merges |
| 2 | #0-200 | depends on #0-199; its other half (per-service preflight evidence), Medium priority |
| 3 | #0-196 | head of the owner's README chain #0-196 → #0-198 → #0-197; built on #0-199's OPTIONS rule |
| 4 | #0-198 | depends on #0-196 (same README paragraph) |
| 5 | #0-197 | depends on #0-198 (same README paragraph); last of the chain |
| 6 | #0-189 | not-ready: the queue stops here until the owner runs /ready; small, oncall test only |
<!-- queue:end -->

## Items

### 1. #0-199 — `OPTIONS` is permitted on every path, so a method-less mapping runs its handler for an anonymous caller
Variant A′ (owner, `/ready` 2026-11-02): `buildCommonSecurity` applies `.cors(withDefaults())` itself and the
`OPTIONS /** permitAll` rule (`SharedSecurityAutoConfiguration:406`) goes, so `CorsFilter` answers a real preflight
before authorization and every other `OPTIONS` is authenticated; tests in `shared` and auth-service's
`UserControllerSecurityTest`, a new ADR-0049 (the next free number; 0048 is the highest), comment-only edits in the six
`SecurityConfig` classes.
· Touches: shared (security; src/test: security), auth-service (auth.config; src/test: auth/api), incident-service,
ingestion-service, notification-service, oncall-service, postmortem-service (`*.config`, comments only), root (README.md
"Infrastructure Hardening", .ai/decisions/0049…, 0048 Status line, .ai/decisions/README.md, BACKLOG.md) — checked
against the code, unchanged.
· Risk high / Complexity medium.
· First because it decides what the three route-walk items expect: their "OPTIONS (#0-199)" paragraphs say "until
#0-199 decides, a method-less mapping reported for `OPTIONS` is a finding for the owner", and #0-199's AC13 rewrites
those paragraphs, which assumes #0-196, #0-198 and #0-197 are still open in `BACKLOG.md` (a done item leaves the file).
Done first, the walks are written once against the final rule (an `OPTIONS` without preflight headers is 401 like every
method). It changes `shared`, so all seven services; the owner merges it, and the queue waits on that merge — at the
head on a Monday, with the week to review it.

### 2. #0-200 — Each service's CORS preflight still passes once the `OPTIONS` rule is gone
Per-service evidence for #0-199 in incident, ingestion, notification, oncall and postmortem: a real preflight with no
token gets 200 with `Access-Control-Allow-Origin`, a bare `OPTIONS` gets 401; `NotificationDenyByDefaultTest` pins
`OPTIONS` as an ordinary method (no longer `404 || 401`). Test code only.
· Touches: incident-service, ingestion-service, notification-service, oncall-service, postmortem-service (src/test:
`*/api`), root (README.md "Infrastructure Hardening", BACKLOG.md) — checked, unchanged.
· Risk low / Complexity medium.
· Depends on #0-199 and is its split-off second half: it closes the regression risk #0-199 opens (the frontend's
"status 0" on a refused preflight), Medium priority, ahead of the Low #0-196/#0-198.

### 3. #0-196 — incident's route walk gets #0-192's shape
`IncidentDenyByDefaultTest`: a positive control, every `RequestMethod` for a method-less mapping, the `/ws/**` exempt
entry removed and a test that a REST mapping under `/ws/` is reported. Test code only.
· Touches: incident-service (src/test: incident/api), root (README.md "Infrastructure Hardening", BACKLOG.md) —
checked, unchanged.
· Risk low / Complexity medium.
· Head of the chain the owner fixed with dependencies (#0-192 → #0-196 → #0-198 → #0-197, one README paragraph);
#0-192 is Done (PR #576). After #0-199 and #0-200 so its walk is written against the final `OPTIONS` rule, and it
follows #0-200's incident test in the same package.

### 4. #0-198 — ingestion's route walk gets #0-192's shape
`IngestionDenyByDefaultTest`: a positive control, a `verify` that the mocked `ApiKeyLookupService` was asked for both
key forms, incident's `isExempt` matching, every `RequestMethod` for a method-less mapping. Test code only.
· Touches: ingestion-service (src/test: ingestion/api), root (README.md "Infrastructure Hardening", BACKLOG.md) —
checked, unchanged.
· Risk low / Complexity medium.
· Depends on #0-196 (reuses its `isExempt` shape and the same README paragraph).

### 5. #0-197 — auth-service's route walks exempt their routes through `PUBLIC_PATHS`
`ApiKeyDenyByDefaultTest` and `IntrospectionTokenDenyByDefaultTest` get a literal `"<METHOD> <pattern>"` exempt set with
a reason each (no `PUBLIC_PATHS`, no `startsWith`), a literal set of key-reachable routes, every `RequestMethod` for a
method-less mapping, and a negative case; ADR-0048's Context updated.
· Touches: auth-service (src/test: auth/api), root (README.md "Infrastructure Hardening", ADR-0048, BACKLOG.md) —
checked, unchanged.
· Risk low / Complexity medium.
· Depends on #0-198 (same README paragraph). Medium priority, but the owner's chain puts it last.

### 6. #0-189 — The on-call schedules' optional-status query has no repository test
A Testcontainers test of `OncallScheduleRepository.findByTenantIdAndOptionalStatus` (`:122`) with a status and
without, over two tenants, as `OncallScheduleOverlapIntegrationTest` does.
· Touches: oncall-service (src/test: a repository integration test) — checked, unchanged.
· Risk low / Complexity low.
· **Not ready**: the queue stops here until the owner runs `/ready #0-189`. Proposed because it is the only other
non-protected, non-design item with criteria that could be readied cheaply (Medium priority, a test only, and the gap
review-general flagged as blocking in four `/seed-bugs` runs).

## Not queued
- #0-193: not-ready, and its Touches names `.ai/rules/review/security.md` and CLAUDE.md "Invariants" (owner) —
  the approach (A or B) is still to decide.
- #0-194, #0-195, #0-190, #0-183, #0-175, #0-171, #0-169, #0-167, #0-156, #0-155, #0-136, #0-116, #0-108: human-only
  or not-ready with Touches in protected paths (`.claude/`, `.github/`, `scripts/factory/`, `.devcontainer/`,
  `architecture-tests/`, `.ai/rules/`).
- #0-201: human-only (Factory guards and `scripts/factory/` are protected); added in this PR at the owner's request,
  found by this run's `check-queue.sh` (#0-199's Touches, fixed below).
- #0-188: human-only (a `shared` logging change for all 7 services, Risk medium).
- #0-153, #0-158, #0-178: human-only design items.
- #0-110: not-ready, Risk high, root `pom.xml` Enforcer plugin (a new plugin: owner's).
- #0-111, #0-112: not-ready, CI checks that need `.github/` (a new workflow step) — human-only or an owner-first split.
- #0-159: not-ready, `**Risk:** medium` is not a value of the convention (low | high), and its approach is "to
  analyse" (Renovate config in `.github/` or root).
- #0-2, #0-4, #0-5, #0-6, #0-14, #0-15, #0-17, #0-22, #0-23, #0-25, #0-28, #0-33, #0-34, #0-36, #0-38, #0-44, #0-45,
  #0-46, #0-58, #0-62, #0-68, #0-69, #0-70, #0-71, #0-72, #0-73, #0-74, #0-86, #0-99, #0-102, #0-105, #0-117, #0-133,
  #0-134, #0-135, #0-151, #0-152: no `**Autopilot:**` line, so never picked; they need `/ready` first.
- #0-3, #0-13, #0-20, #0-24, #0-31, #0-32, #0-35, #0-37, #0-40, #0-41, #0-43, #0-48, #0-55, #0-56, #0-64, #0-65, #0-66,
  #0-67, #0-75, #0-77, #0-79, #0-85, #0-87, #0-94, #0-95, #0-97, #0-98, #0-101, #0-106, #0-107, #0-139, #0-140, #0-143:
  type `design`.

## Changes to BACKLOG.md in this proposal
- #0-199: Touches reworded, same reach — the nested `(Status line)` inside `root (…)` closed the parenthesis early,
  so `check-queue.sh` read `.ai/decisions/README.md` and `BACKLOG.md)` as modules; now `…0048-every-route-is-walked-deny-by-default.md:
  Status line only`, with no nested parentheses.
- Otherwise none. Every queued item already has a `**Touches:**` line, and the code (the six `SecurityConfig` classes,
  `SharedSecurityAutoConfiguration:406`, the `*DenyByDefaultTest` classes, `OncallScheduleRepository:122`) agrees
  with each. No unrecorded dependency found: no handler method in auth, incident or ingestion has a method-less
  `@RequestMapping`, so #0-196/#0-198/#0-197 could run before #0-199; the order after it is queue order only.
