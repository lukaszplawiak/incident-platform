# ADR-0014: Audit outbox

- **Status:** Accepted
- **Backlog:** #0-84, #0-4, #0-93, #0-103, #0-92
- **Source:** migrated verbatim from `.ai/context/project.md`, section "Known Invariants and
  Limitations" (2026-10-05). The text below is the original record; it was not rewritten into the
  Context / Decision / Consequences form. New ADRs use `_template.md`.
- **Updated:** 2026-10-09 — the owner's later edit of this record in `project.md`, carried over verbatim.

## Record

(#0-84, done in two PRs: #449 `shared` + auth- and incident-service; the second
notification-, escalation- and postmortem-service): `AuditEventPublisher.publish*`
INSERTs into the service's own table (`audit.outbox.table`: `auth_audit_outbox` V27,
`incident_audit_outbox` V14, `notification_audit_outbox` V8, `escalation_audit_outbox` V7,
`postmortem_audit_outbox` V5) through `AuditEventStore` -> `AuditOutbox` (JDBC, joins the caller's JPA
transaction; the interface keeps JDBC types out of the publisher, as ingestion-service has no
`spring-jdbc`), so an event exists iff its action committed and a Kafka outage stalls no request.
- Consequence: an audit call followed by a throw in the same transaction loses its event. A refusal is
  audited after the rollback: `MfaService.verifyMfaToken` / `verifyWithBackupCode` run in a
  `TransactionTemplate`, set rollback-only on a wrong code (the consumed MFA token comes back), then publish
  `MFA_VERIFY_FAILED` and throw. Not a nested `REQUIRES_NEW` write: tried first, found in review to hold two
  pooled connections per wrong code (auth-service's pool is 5).
- `AuditOutboxRelay` (`shared`, `@Scheduled` every `audit.outbox.poll-interval-ms`, ShedLock
  `audit-outbox-relay-<table>` since all services share one `shedlock` table, taken only after a lock-free
  `AuditOutbox.anyDue()` says a row is due, as taking it writes to that shared table; a poll with nothing due
  leaves the pause's failed-run count alone): hands a batch
  (`batch-size`, 100) to the producer (`sendForRelay`), then awaits the acks within `send-timeout` (5 s);
  up to `max-batches-per-run` (10) full batches a run; lock at most `send-timeout x batches + 30 s`. Acked
  rows SENT in one `UPDATE ... WHERE id = ANY(?)` per batch; a failed row backs off 5 s doubling to
  5 min (due again by the database's clock, `make_interval`), never given up. Only Kafka's own failures
  (`RetriableException`, timeouts, anything unknown) end the batch and pause the relay by the same backoff;
  a record Kafka refuses for itself (`RecordTooLargeException`, serialization) is backed off alone
  (`isKafkaFailure`). `markSent` failing after the ack is logged, not counted as a failure (resent,
  deduplicated). The publisher refuses (IllegalArgumentException, failing the action) an
  event the trail cannot store: no tenant/resource/source, a field over its `audit_events` column, a payload
  over 256 KiB (`MAX_PAYLOAD_BYTES`), so no row is ever one Kafka refuses for ever; also a metadata key
  named like a secret (`looksSecret`: password, secret, credential, totp, *token, rawKey, apiKey).
  Due rows are read `ORDER BY next_attempt_at, created_at`, the due index's order. SENT rows purged after
  `retention`, 5,000 per statement, at most `MAX_PURGE_CHUNKS` (100) statements a run (WARN when capped),
  by the database's clock. Table names: `[a-z][a-z0-9_]`, at most 45 characters (`AuditOutbox.
  MAX_TABLE_NAME_LENGTH`: the 19-character lock prefix + the name must fit `shedlock.name VARCHAR(64)`),
  checked by `AuditOutbox`'s constructor and by `AuditOutboxProperties`. `relayNow()` runs once ignoring the pause
  (break-glass). Defaults live in `AuditOutboxProperties`.
- Outbox tables (all five have the same shape): `tenant_id NOT NULL`; indexes `_due (next_attempt_at, created_at)`,
  `_pending_created (created_at)` for the gauge's `min`, `_sent (sent_at)` for the purge, all partial.
- Env overrides: `AUDIT_OUTBOX_POLL_INTERVAL_MS` (poll), `KAFKA_PRODUCER_MAX_BLOCK_MS` (5 s in auth-,
  incident-, notification- and postmortem-service, or `send()` blocks Kafka's default 60 s per run while
  metadata is missing; NOT set in escalation-service on purpose: its `IncidentEscalatedEvent` is sent once,
  fire-and-forget, never retried (#0-4), so 5 s would turn a short Kafka outage into a lost escalation; its
  relay has its own virtual thread and a lock (80 s) longer than one 60 s block),
  `SCHEDULING_POOL_SIZE` (4, auth-service only, which runs on platform threads, so a slow relay run does
  not hold its other `@Scheduled` jobs; the other four have virtual threads, where Boot schedules each
  run on its own virtual thread and `pool.size` has no effect).
- Where the write goes in the three later services: postmortem: already in the `@Transactional` with
  the `save` (unchanged). notification: inside `NotificationPersistenceService.recordChannelSent /
  recordChannelFailed / markUndeliverable` with the row (every failed send audited now, not only a
  `NotificationException`; a null error message stored as "unknown", `Map.of` refuses null);
  `processEntry` records a delivery outside the send's try, and a failed record of a delivered message is
  an ERROR + `audit.event.unrecorded{event_type}`, neither a failed send nor a failed entry (a FAILED entry
  is never retried, so the other channels would go unsent). escalation: `ESCALATION_SCHEDULED` inside
  `EscalationService.scheduleLevel2Escalation` (only when a task is inserted); `ESCALATION_FIRED` and
  `ESCALATION_NOTIFICATION_FAILED` via `EscalationScheduler.auditAfterTheFact` (own transaction; failure ->
  ERROR + `audit.event.unrecorded`, so it neither counts a failed attempt against an escalated task nor
  skips level 2). `SlackActionService`'s `SLACK_ACK_MESSAGE_UPDATE_FAILED` is a standalone write on the
  `@Async` thread, guarded the same way (ERROR + `audit.event.unrecorded`). The alert
  `AuditEventUnrecorded` (critical) watches that counter; every such ERROR line starts "Audit event not
  recorded" (or, for a delivered notification, "Notification delivered but not recorded"). A failed send's own record (`recordChannelFailed`) is
  guarded the same way, so the remaining channels are still tried; `markUndeliverable` alerts the operator
  in a `finally`, whether or not its write succeeded.
- Error text in audit events goes through `AuditText` (`shared`): `error(msg)` = one line, at most 500
  characters (else an unbounded Gemini/SMTP message could exceed 256 KiB and roll the action back);
  `unexpected(e)` = "Unexpected error: <ExceptionClass>" for exceptions the code did not anticipate
  (notification's generic catch, both escalation failure events), never their message: it can quote a
  URL with a token, an internal host or a response body, and the trail is tenant-readable. The full
  message stays in the ERROR log (and, for a channel's own failure, in `notification_log`). postmortem:
  `PostmortemRetryScheduler.failureText` records a fixed text (`GEMINI_FAILED`) for a `GeminiException`
  (its message is built from the HTTP client's or Jackson's and can quote Gemini's response) and the type
  for anything else, on the postmortem and in the event; the full exception is logged. A channel's own
  `NotificationException` carries a `NotificationFailureReason` and at most a provider code checked as
  `[a-z0-9_]{1,64}` (Slack's `error`, `http_<status>`); its message is built from those alone, so even code that
  logs or stores `getMessage()` gets the platform's words, and the provider's error is only its cause (#0-93).
  Email reasons come from walking the exception, `MailSendException.getMessageExceptions()` and
  `MessagingException.getNextException()` included. A 5xx on RCPT is the tenant's rejected address only when the
  reply names the address (Angus' `SMTPAddressFailedException` with an enhanced `5.1.x`, or 550/551/553 without
  one, and only to `RCPT`); "550 5.7.1 Relaying denied" is a policy refusal, the platform's, so `EMAIL_FAILED`
  at ERROR. An `AddressException` is the tenant's only when its `getRef()` is the recipient (a malformed `from`
  is the operator's).
  Slack's Web API answers most failures with HTTP 200 and `"ok": false`: both `chat.postMessage` and
  `chat.update` check it (`requireOk`); a missing `error` is `SLACK_REJECTED`, a token Slack refuses
  (`invalid_auth`, `token_revoked`, `account_inactive`, HTTP 401) `SLACK_AUTH_FAILED`, at ERROR. A refused
  broadcast does not stop the on-call DM: the send counts as delivered when the DM went, the broadcast's failure
  is logged and counted (`notification.channel.failed{channel="SLACK_BROADCAST"}`), not a `notification_log` row
  (one row per channel, and the Slack row is the delivery's). A bare 550/551/553 without an enhanced code is
  still read as the recipient's (RFC 5321's "mailbox"), accepted: a relay sending a bare "550 Relaying denied"
  is the case it misses. The Slack calls live in `SlackApiClient` (#0-103: they were methods of the channel,
  called on `this` past the `@Retry` proxy, so no send was ever retried). It has its own timeouts
  (`notification.channels.slack.connect-timeout`/`read-timeout`, 3 s/5 s; the bare `RestClient.Builder` set no
  read timeout; the read timeout also cuts a trickling body), never follows a redirect (pinned: the request carries
  the tenant's bot token), and a retried post may show twice, accepted (Slack takes no idempotency key). Its fallbacks do
  not log, the caller does (WARN/ERROR by permanence). Resilience4j calls a fallback for every exception, so
  the fallback passes an already classified `NotificationException` through and rethrows anything that is not
  the HTTP client's (recorded by type); `SlackActionService.tryUpdateMessage` therefore catches
  `RuntimeException` too, or one bug would stop the other channels' ACK updates.
- Counters behind alerts are registered at zero when their owner is built (`UnrecordedAuditEvents` in
  `shared` for `audit.event.unrecorded`, the reasons of `audit.events.rejected` in `AuditEventConsumer`;
  as `AuthEmailScheduler` does): a counter created at its first increment starts its series at 1, and
  `increase()` misses that first failure, so the alert would wait for a second one.
- The consumer logs and dead-letters a rejected record's reason without the database's or parser's text
  (`constraintReason`: constraint name and SQLState; `unreadableReason`: JSON error type and position):
  Postgres's `Failing row contains (...)` and Jackson's messages quote the event's content.
- At least once: `AuditEventMessage.eventId` (= the row id) is stored in `audit_events.event_id`
  (incident-service V12), unique per `(tenant_id, event_id)` (V13, partial, `CREATE UNIQUE INDEX
  CONCURRENTLY` alone in its file so Flyway runs it outside a transaction). CONCURRENTLY needs
  `spring.flyway.postgresql.transactional-lock: false` (incident-service): with Flyway's default
  transactional lock the build waits forever on Flyway's own open transaction (seen in the migration test).
  `AuditEventConsumer` resolves the tenant per record with `TenantKafkaRecordResolver` (since #0-92: the
  payload's valid tenant, a header must equal it), sets/clears `TenantContext`; a refused tenant is
  `tenant_mismatch` or `tenant_invalid`, dead-lettered with no tenant. It acknowledges as duplicates only violations of `IDEMPOTENCY_KEYS` (V10 offset key, V13), by
  Hibernate's constraint name; any other violation, unreadable JSON or a tenant mismatch is rejected: ERROR
  log, `incidents.dead-letter` through `DeadLetterPublisher.deadLetterThenAcknowledge` (acknowledged only once
  Kafka has the copy, else `nack(5 s)`; a failed save: transient -> nack, else reason `unexpected`), counter `audit.events.rejected{reason}` and alert `AuditEventsRejected`
  (critical) — the outbox already counted the event delivered, so nothing else would show the gap. The non-transactional Flyway lock assumes no transaction-pooling proxy.
  `occurredAt` is the producer's time, not the consume time.
- Metrics, read from the table at scrape time (cached 5 s, NaN if unreadable; the age measured by the
  database's clock, plus the time since the read), on every replica:
  `audit_outbox_pending`, `audit_outbox_oldest_pending_age_seconds`; counters `audit_outbox_sent_total`,
  `audit_outbox_send_failed_total`. Alert `AuditOutboxBacklog` (oldest pending > 10 min for 5 min,
  critical); a down service is `PlatformServiceDown`.
- No direct send any more: `AuditEventPublisher` and `AuditEventKafkaSender` are beans of
  `AuditOutboxConfiguration` only (`@ConditionalOnProperty(audit.outbox.table)`), so a service that injects
  the publisher without a table fails at startup (ingestion- and oncall-service have none and need none;
  oncall's web tests still `@MockitoBean` it, harmless). `@Retryable` on the sender was inert (no
  `@EnableRetry` anywhere) and is gone. Audit metadata never holds a secret: the outbox keeps it in plain text 7 days.
