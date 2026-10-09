# ADR-0013: Tenant id and Kafka tenant

- **Status:** Accepted
- **Backlog:** #0-91, #0-92, #0-96
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.

## Record

(#0-91, #0-92): one tenant id format, `TenantIds.SLUG` (shared; a `CHECK` on every
table with a `tenant_id`: auth V28, incident V15, notification V9, escalation V8, postmortem V6, oncall V7;
each service's integration test fails for a new table without one). Refused where it enters: `JwtUtils` issue (user and service tokens), `JwtAuthFilter`
read (invalid claim = unauthenticated), auth-service public `X-Tenant-Id` (400 via `AuthController.checkedTenant`),
`TenantContext.set` (`InvalidTenantIdException`, an IAE; null and blank too), `DevTokenController`,
`AuditEventPublisher.checkStorable`, `ApiKeyAuthFilter` (a lookup returning an invalid tenant = 401 like an
invalid key, not a 500 from `TenantContext.set`), STOMP `CONNECT` (`StompAuthChannelInterceptor`: claim
filtered by `TenantIds::isValid`; a SUBSCRIBE refusal does not log the client's destination). A new entry
point that takes a tenant needs the same explicit check. Kafka: the payload's
`tenantId` is the truth, the header a copy, written only by `TenantRecords.forTenant` (`IncidentEventKafkaSender`
had none before: it relied on the interceptor + ThreadLocal; `sendRawSync` now takes the tenant).
`TenantKafkaProducerInterceptor` writes nothing: counts `kafka.records.produced.tenant.invalid{missing|invalid}`
in Micrometer's global registry (Kafka instantiates it, not Spring); exempt only a dead-letter record built by
`TenantRecords.withoutTenant` (marker `X-Tenant-Unresolved`, on a `.dead-letter` topic: the topic name alone
exempts nothing); `AuditOutboxRelay` treats only `InvalidTenantIdException` as the row's fault; registered in
auth/ingestion/incident/notification/escalation/postmortem. `TenantKafkaRecordResolver(objectMapper,
meterRegistry)`: missing/invalid payload tenant or missing/invalid/mismatched header -> `TenantResolutionException`
(an IAE, so every listener's poison-pill path dead-letters it) + `kafka.records.tenant.rejected{reason}`
(`header_missing` too: every sender writes the header, so a record without one is not the platform's).
Every loop that sets `TenantContext` from a row (5 scheduler classes, postmortem's with two loops; `AuditOutboxRelay`) does it inside the
per-row `try`: one bad row must fail alone, not end the batch for every tenant (incident outbox: inside
`processOne`, so `markFailed` runs once; never a second `markFailed` against a failing database).
`IncidentEventKafkaSender.sendRawSync` refuses a row whose tenant is not its payload's `tenantId` (consumers
would dead-letter it as a mismatch). `AlertIngestionService.ingest` checks the tenant before normalizing or
setting a dedup key, and releases the key when the publish throws synchronously. A dead-letter topic must be
named `*.dead-letter` (`DeadLetterPublisher` refuses another name at construction: `withoutTenant` needs it).
Kafka record fate (backlog #0-96): every listener is `MANUAL_IMMEDIATE` and never `return`s without
acknowledging — the next record's ack would commit the offset past it (`DeadLetterPublisherKafkaIntegrationTest`, real broker).
Poison pill -> `DeadLetterPublisher.deadLetterThenAcknowledge` (acks only once Kafka has the copy, else
`nack(5 s)`); transient (`KafkaFailures.isTransient`: TransientDAE, RecoverableDAE,
DataAccessResourceFailureException, CannotCreateTransactionException, SQL transient/recoverable, any cause depth)
-> `redeliverLater(record, tenant, ack, cause)` (nack); a last `catch (Exception)` -> `redeliverIfTransientElseDeadLetter`. Counter
`kafka.records.redelivery.requested{transient|dead_letter_failed}`. No fire-and-forget `publish` exists any more;
ingestion uses `publishAndWait` (normalization failure) or `publishAsync` + `await` and maps `DeadLetterNotStoredException` to 503 + Retry-After 10
(`INGESTION_UNAVAILABLE`). A listener must not be `@Transactional` (its ack would commit the offset before the
DB commit): `IncidentEscalationEventConsumer` writes through `IncidentCommandService.recordEscalationLevel`.
Bounds: a copy waits <= 5 s in all (`deadLetterTemplate` = the service's producer settings with max.block 2 s, a
producer of its own closed by `DeadLetterPublisher.destroy`); `requireFitsPollInterval(max.poll.records, interval)`
in each consumer's KafkaConfig (interval 120 s in incident/escalation/postmortem, 300 s notification);
`RecordRedeliveries` (per group + partition, keyed by the head offset; `KafkaUtils.getConsumerGroupId()`) -> past
`kafka.consumer.redelivery-deadline` (PT30M) dead-letter + `kafka.records.redelivery.gave_up` + alert
`KafkaRecordRedeliveryGaveUp`; `KafkaRecordRedeliveryStuck` after 15 min of nacks. Accepted trade-off: a database
outage longer than the deadline dead-letters one record per partition per deadline (an audit event is then missing
from the trail until replayed by hand; it also raises `AuditEventsRejected{reason=gave_up}`). A copy's payload is
cut to 128 KiB UTF-8. What a copy or log line says: `KafkaFailures.reason` (a message only for
TenantResolution-, UnrecognizedSeverity-, UnreadableRecordException; else `KafkaFailures.describe` = type + first
platform frame); an unexpected exception -> `AuditText.unexpected` in the copy, `describe` in the log; a header
value (`X-Event-Type`) logged through `AuditText.error`. Ingestion (`DeadLetterCopies`): copies started with
`publishAsync`, awaited together with one deadline counted from the wait (`await(copies, clock.instant())`), none
started after one failed at once, the raw payload copied once per request, the dedup keys of alerts kept only by
a copy released when it is not stored (an alert is registered before its copy starts); producer max.block.ms 5 s.
Missing `X-Event-Type` -> copy under `TenantKafkaRecordResolver.trustedTenantOrNull` (not counted).
Moving to `DefaultErrorHandler` + DLT replay: #0-97.
`TenantKafkaRecordInterceptor` MDC: valid header, else
`_missing`/`_invalid` (not slugs, so never a real tenant; `"unknown"` was a valid slug); the MDC shows the
header's claim until the consumer resolves the record (a refused record's log lines carry the claimed
tenant in the MDC prefix and `tenant=null` in the message). `kafka.records.received` has no `tenant` tag any
more (a forged well-formed header opened a series per value: never tag a metric with a record's value). DLT: resolved tenant
or null (key `service:_none`, no header), reason through `AuditText.error`. Consumers set no placeholder
tenant. Alerts `KafkaRecordsTenantRejected` (critical) / `KafkaRecordsWithoutTenant` (high).
