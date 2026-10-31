# Seeded-defect patterns

Owned by the maintainer. The `seed-bugs` workflow takes one pattern, plants it in a throwaway copy of a
recently merged change, runs the review panel on it and records who caught it. A defect that escaped to
`main` becomes a new pattern here (the audit report proposes it; the owner adds it).

Each pattern: an id, the dimension that must catch it, the rule it violates, and how to plant it. Plant
the smallest edit that produces the defect in real code of the change — never a comment that announces it.

| Id | Dimension | Rule | Defect to plant |
|---|---|---|---|
| P-01 | security | SEC-06 | drop the tenant predicate from a `@Query` (JPQL or native) or a derived query in a repository the change touches |
| P-02 | security | SEC-04 | in a `@KafkaListener`, set `TenantContext` once for the whole batch instead of per record, or move `TenantContext.clear()` out of `finally` |
| P-03 | security | SEC-03 | read the tenant from a request header or body field instead of the principal |
| P-04 | security | SEC-31 | log a token, API key or the full exception message of an auth failure at INFO |
| P-05 | security | SEC-10 | drop the `@PreAuthorize` of an endpoint the change adds or changes, or add its path to `PUBLIC_PATHS` |
| P-06 | architecture | ARC-22 | send a lifecycle or audit event with `KafkaTemplate.send` directly instead of through the outbox |
| P-07 | architecture | ARC-23 | drop the ShedLock lock (`@SchedulerLock` or `executeWithLock`) of a `@Scheduled` job that writes, sends or claims work; never a job that runs per replica on purpose |
| P-08 | architecture | ARC-25 | initialise a `@Version Long version = 0L` |
| P-09 | architecture | ARC-02 | add a hand-written retry loop where `AuthEmailRetryPolicy`-style deadline retries exist |
| P-10 | performance | PERF-01 | call a repository method (or reach a lazy association) once per element of a collection, instead of one batched query (N+1), on a request path |
| P-11 | performance | PERF-23 | move an outbound HTTP call, a direct Kafka send or an email inside a `@Transactional` method (or call it from one) |
| P-12 | performance | PERF-02 | replace a paged query with `findAll()` on a per-tenant table |
| P-13 | migration | MIG-04 | add a `NOT NULL` column without a default to an existing table |
| P-14 | migration | MIG-02 | retired 2026-10-26 (#0-115): cannot be planted, since the seeder may touch only the change's files and a migration the change adds is not yet on the base |
| P-15 | general | GEN-03 | replace a state assertion in a test with a bare `verify(...)` |
| P-16 | general | GEN-08 | add `@Disabled` to a failing test without a reason |
| P-17 | docs | DOC-11 | change a method to throw instead of returning `null`, leave its Javadoc `@return null if…` |
| P-18 | docs | DOC-01 | add a required config key without the README "Step 2" template line |
| P-19 | migration | MIG-05 | add a `FOREIGN KEY` or `UNIQUE` constraint to an existing table in one step: drop its `NOT VALID` (validated later), or skip the check that no row violates it; on a host where nothing else guarantees the rows (V31 locks and backfills first, so dropping `NOT VALID` there is within MIG-05: mig-after-p19) |
| P-20 | k8s | K8S-08 | add a `<repository>`, `<pluginRepository>` or `<plugin>` to a `pom.xml` the change touches |
| P-21 | k8s | K8S-10 | drop `@{argLine}` from a surefire `<argLine>`, or replace it with `${argLine}` |
| P-22 | k8s | K8S-06 | remove a `readinessProbe`, a `livenessProbe` or the `resources` block from a Deployment the change touches |
| P-23 | architecture | FAC-A02 | in a workflow, let a missing or malformed agent answer go on: drop the `!answer \|\|` of a guard that stops the item, or read a field with `?.` where its absence must stop |
| P-24 | architecture | FAC-A06 | leave a field the workflow reads out of its `agent()` call's schema, or type it only as required |

P-23 and P-24 are planted by hand, not by `seed-bugs`: their hosts change Owner-only paths, which `seed-branch.sh`
refuses. The host's code is reverted on `review/<8 hex>` (`Revert "<subject>"`), replayed with the plant committed
into the change as one commit (as `seed-fold.sh` does), and the session starts security and architecture with the
autopilot prompt (`Autopilot review. Branch review/<8 hex>. mergeBase: <the revert>. round: 1.`) and a sentence that
`factory.md` applies (the diff touches Owner-only paths), not a manual `/review` of uncommitted changes: F1's manual
review reached the original 4 times of 4 (backlog #0-185, ADR-0044 decision 6). A host changes only
`.claude/workflows/` and `scripts/factory/`, never a hook, a setting, an agent, a skill or a rule: those are what
`/review` runs under.

## Clean changes (backlog #0-180)

A clean pattern plants a correct change that a careless reading of its rule would block: the rule's deliberate
exception, with the reason the rule asks for. The run measures false alarms, not catches: a dimension that stops the
change at the planted line under the pattern's rule is a false alarm, one that notes it, or stops it under another rule
(a test asked for under GEN-01), is recorded as raised. Plant the change so
that it is right, as the existing example it names is; a plant that is not right measures nothing (the owner reads the
row before it lands). The panel's prompt is the same as for a defect: rotate clean and defect runs in a series.

| Id | Dimension | Rule | Correct change to plant |
|---|---|---|---|
| C-01 | architecture | ARC-23 | add a `@Scheduled` method that only reads and keeps a per-replica value (a gauge, a local cache), with no ShedLock and a Javadoc saying why it runs on every replica, as `OperatorTenantBootstrap.refreshPendingGauge` does; it writes, sends and claims nothing |
| C-02 | architecture | ARC-29 | change a row that has one writer by a state-guarded conditional UPDATE (`WHERE id = ? AND status = ?`, row count checked) instead of `@Version`, as `AuthToken.markUsedIfUnused` does; only where the change's own code shows that writer is the only one |
| C-03 | security | SEC-08 | add a write path a suspended tenant must still reach (one that only ends its own session) to `tenant-status.read-only.allowed-writes`, with the reason beside it; never a path that changes the tenant's data |

