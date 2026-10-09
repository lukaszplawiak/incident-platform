# Audit decisions ledger

Every audit recommendation and what the owner decided. Written only by `/apply-audit` (run by the
owner); read by the audit agents at the start of each cycle, so that a rejected recommendation is not
proposed again without new evidence, and an accepted one is checked against its metric.

| Id | Agent | Severity | Decision | Applied in | Owner note | Metric check (next audit) |
|---|---|---|---|---|---|---|
