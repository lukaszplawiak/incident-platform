# Execution queue — 2026-11-11

<!-- queue:start -->
| # | Item | Why here |
|---|---|---|
| 1 | #0-189 | the only ready item; Medium, oncall test only, Risk low |
| 2 | #0-58 | without an Autopilot line: the queue stops here until the owner runs /ready; Medium, tests for the rest of shared (a guard for all 7 services) |
| 3 | #0-25 | without an Autopilot line: stops the queue until /ready settles its approach and migration number; Medium, notification-service |
<!-- queue:end -->

## Items

### 1. #0-189 — The on-call schedules' optional-status query has no repository test
A new Testcontainers test class (`postgres:16-alpine`) in `oncall-service/src/test/java/com/incidentplatform/oncall/repository/`
runs `OncallScheduleRepository.findByTenantIdAndOptionalStatus` (`:122`) with a status and with `null`, over two
tenants and all three statuses, and checks paging (size 1, latest `startsAt`, `totalElements`).
· Touches: oncall-service (src/test: oncall/repository). Checked against the code and unchanged: the query is at `:122`, only
`OncallScheduleServiceTest` (mocked) calls it, and `OncallScheduleOverlapIntegrationTest` is the only test in that package.
· Risk low / Complexity low.
· First because it is the only `ready` item (readied in PR #597). It is Medium priority, test code only, and it fills
the gap review-general reported as blocking in four `/seed-bugs` runs.

### 2. #0-58 — `shared`'s Kafka tenant classes have no tests of their own (what is left of it)
Since #0-91/#0-92 the Kafka classes have their own tests. Two classes are left: `GlobalExceptionHandler`
(`shared/exception`) and `UnauthorizedEntryPoint` (`shared/security`). Neither has a test class in `shared` (the
`SharedSecurityAutoConfiguration*Test`s only construct the entry point). The work is unit tests of each class's contract.
· Touches (proposed, for `/ready`; not written, the item has no `**Autopilot:**` line): shared (src/test: exception, security).
· Risk low / Complexity low (test code only, if `/ready` keeps the "Also below 60%" list out of scope).
· Next because it is a guard. Both classes are on the error path of every service, so tests where they are defined
protect every later item that touches error handling or the 401 answer. It is Medium priority and small. **Without an
Autopilot line**: the queue stops here until the owner runs `/ready #0-58`. `/ready` should give it numbered criteria, and
should either narrow the title to the two classes or close the item and open a new one for them.

### 3. #0-25 — `NotificationQueueEntry` has no `@Version`
Optimistic locking for the notification outbox entity (still none: `NotificationQueueEntry:53`). It needs a new
`version` column, `@Version Long` left uninitialised, and a rule for how the scheduler handles
`OptimisticLockingFailureException`. The item also names an alternative, the conditional `UPDATE ... WHERE status IN (...)`
of auth-service's `AuthEmailOutbox` (#0-52).
· Touches (proposed, for `/ready`; not written): notification-service (domain, repository, scheduler, db/migration; src/test).
· Risk low (an added nullable/defaulted column, not destructive) / Complexity medium.
· Medium priority and a decided pattern of `architecture.md` that this entity breaks. It comes after #0-58 because that is
a cheaper guard. **Without an Autopilot line**: the queue stops here until `/ready`. Before that, the owner has to pick
the approach (`@Version` or conditional updates), which is an open decision under `ready.md` point 2. The item's text is
also stale: it says the migration "is V8", but notification-service already has V1 to V10, so the next one is V11.

## Not queued
- #0-2, #0-4, #0-5, #0-6, #0-14, #0-15, #0-17, #0-22, #0-23, #0-28, #0-33, #0-34, #0-36, #0-38, #0-44, #0-45, #0-46,
  #0-62, #0-68, #0-69, #0-70, #0-71, #0-72, #0-73, #0-74, #0-86, #0-99, #0-102, #0-105, #0-117, #0-133, #0-134, #0-135,
  #0-151, #0-152: no `**Autopilot:**` line, so they need `/ready` first. #0-58 and #0-25 were chosen ahead of them
  because they are Medium priority and test-first or pattern-restoring. Several of these are `.github/` work
  (#0-2, #0-62, #0-68, #0-69, #0-70, #0-134, #0-135, #0-151, #0-152), which would be human-only. #0-15 is deferred to
  #0-35. #0-133 is an event contract change (Risk high).
- #0-208: not-ready. Its approach (a)/(b)/(c) is still to decide, and (a) is a new inter-module test dependency. It
  reaches five services plus `shared`, beyond `ready.md` point 4 (one service plus `shared`).
- #0-193: not-ready. Its Touches names `.ai/rules/review/security.md` and CLAUDE.md "Invariants" (owner), and A or B
  is still to decide.
- #0-110: not-ready, Risk high (a new Maven Enforcer plugin in the root `pom.xml`, the owner's).
- #0-111, #0-112: not-ready. They are CI checks that need a new `.github/` step, so human-only or an owner-first split.
- #0-159: not-ready. Its `**Risk:** medium` is not a value of the convention, and its approach is "to analyse".
- #0-190: not-ready, and its Touches is in protected paths (`scripts/factory/`, `.claude/`, `.github/`).
- #0-108, #0-136, #0-155, #0-156, #0-167, #0-169, #0-171, #0-175, #0-183, #0-188, #0-194, #0-195, #0-201, #0-204,
  #0-205, #0-207, #0-210, #0-211, #0-212, #0-213, #0-214, #0-216, #0-217: human-only. Most are factory or CI work in
  protected paths. #0-188 is a `shared` logging change for all 7 services.
- #0-3, #0-13, #0-20, #0-24, #0-31, #0-32, #0-35, #0-37, #0-40, #0-41, #0-43, #0-48, #0-55, #0-56, #0-64, #0-65,
  #0-66, #0-67, #0-75, #0-77, #0-79, #0-85, #0-87, #0-94, #0-95, #0-97, #0-98, #0-101, #0-106, #0-107, #0-116,
  #0-139, #0-140, #0-143, #0-153, #0-158, #0-178, #0-202: type `design` (and #0-116, #0-153, #0-158, #0-178, #0-202
  are also human-only).

## Changes to BACKLOG.md in this proposal
- None. #0-189's Touches agrees with the code. #0-58 and #0-25 have no `**Autopilot:**` line, so there is no line to
  put a Touches under. Their proposed Touches are above, for `/ready`. No unrecorded dependency was found: #0-189 is in
  oncall-service, #0-58 in `shared` tests, #0-25 in notification-service, and none of them needs another.
