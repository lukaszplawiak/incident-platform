# ADR-0002: Rate limiting is Redis-backed (decision reversed, backlog #67)

- **Status:** Accepted
- **Backlog:** #67
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

ingestion-service rate limiting uses bucket4j with a Redis `ProxyManager`, protected by
`@CircuitBreaker` and failing open. The earlier in-memory design was reversed because its
buckets were per-pod (effective limit = limit × replicas) and lived in unbounded maps keyed
by the caller-controlled `X-Forwarded-For` header. Do not reintroduce in-memory per-key state.
