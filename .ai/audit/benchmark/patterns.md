# Seeded-defect patterns

Owned by the maintainer. The `seed-bugs` workflow takes one pattern, plants it in a throwaway copy of a
recently merged change, runs the review panel on it and records who caught it. A defect that escaped to
`main` becomes a new pattern here (the audit report proposes it; the owner adds it).

Each pattern: an id, the dimension that must catch it, the rule it violates, and how to plant it. Plant
the smallest edit that produces the defect in real code of the change — never a comment that announces it.

| Id | Dimension | Rule | Defect to plant |
|---|---|---|---|
| P-01 | security | SEC-06 | drop the `tenant_id` predicate from a native `@Query` in a repository the change touches |
| P-02 | security | SEC-04 | in a `@KafkaListener`, set `TenantContext` once for the whole batch instead of per record, or move `TenantContext.clear()` out of `finally` |
| P-03 | security | SEC-03 | read the tenant from a request header or body field instead of the principal |
| P-04 | security | SEC-31 | log a token, API key or the full exception message of an auth failure at INFO |
| P-05 | security | SEC-10 | add a new endpoint's path to `PUBLIC_PATHS` |
| P-06 | architecture | ARC-20 | send an event with `KafkaTemplate.send` directly instead of through the outbox |
| P-07 | architecture | ARC-20 | add `@Scheduled` to a method without `@SchedulerLock` |
| P-08 | architecture | ARC-20 | initialise a `@Version Long version = 0L` |
| P-09 | architecture | ARC-02 | add a hand-written retry loop where `AuthEmailRetryPolicy`-style deadline retries exist |
| P-10 | performance | PERF-01 | call a repository method inside a loop over a lazy collection (N+1) on a request path |
| P-11 | performance | PERF-23 | move an outbound HTTP call inside a `@Transactional` method |
| P-12 | performance | PERF-02 | replace a paged query with `findAll()` on a per-tenant table |
| P-13 | migration | MIG-04 | add a `NOT NULL` column without a default to an existing table |
| P-14 | migration | MIG-02 | edit an existing `V<n>` migration instead of adding one |
| P-15 | general | GEN-03 | replace a state assertion in a test with a bare `verify(...)` |
| P-16 | general | GEN-08 | add `@Disabled` to a failing test without a reason |
| P-17 | docs | DOC-11 | change a method to throw instead of returning `null`, leave its Javadoc `@return null if…` |
| P-18 | docs | DOC-01 | add a required config key without the README "Step 2" template line |
