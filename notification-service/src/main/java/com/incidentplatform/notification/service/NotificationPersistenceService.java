package com.incidentplatform.notification.service;

import com.incidentplatform.notification.domain.NotificationLog;
import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.domain.UndeliverableReason;
import com.incidentplatform.notification.repository.NotificationLogRepository;
import com.incidentplatform.notification.repository.NotificationQueueRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.audit.AuditText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Short, independent transactions for {@link NotificationQueueEntry} and
 * {@link NotificationLog} writes made while processing a notification —
 * backlog #42.
 *
 * <h2>Why this class exists</h2>
 * {@code NotificationService.processEntry(...)} previously carried
 * {@code @Transactional} on the whole method — including
 * {@code NotificationRouter.route(...)} (an HTTP call to oncall-service)
 * and, per routed channel, {@code channel.send(...)} (a further HTTP call
 * to Slack/SMTP/an SMS gateway) — holding a database connection open for
 * the combined duration of an oncall lookup plus up to three separate
 * external sends. Under a large backlog or a slow/degraded downstream
 * (any of oncall-service, Slack, the SMTP relay, or the SMS gateway),
 * this risked holding a connection for a long time per entry, potentially
 * exhausting the pool. The same class of problem already fixed in
 * {@code EscalationScheduler} (backlog #39) and present, in a smaller
 * form, in {@code NotificationScheduler}'s own catch block (also fixed
 * as part of backlog #42 — see that class's Javadoc).
 *
 * <p>Fixed by removing {@code @Transactional} from {@code processEntry}
 * entirely — every external call now happens with no open transaction,
 * and each database write goes through this class instead: a short,
 * independent transaction with no HTTP call in progress while it's open.
 *
 * <h2>Bonus correctness improvement, not just a style fix</h2>
 * Under the old single-transaction design, Hibernate's default flush
 * timing meant {@code NotificationLog} rows for channels already
 * successfully sent earlier in the same {@code processEntry} call were
 * not necessarily durable until the <em>whole</em> method's transaction
 * committed — so a crash partway through a multi-channel entry (e.g.
 * after Slack succeeded but before Email was attempted) could lose the
 * record of Slack's success along with everything else, causing the next
 * pickup of this still-PENDING entry to see no log row and resend via
 * Slack too — a duplicate notification. Each channel's outcome now
 * commits immediately in its own short transaction, so a crash partway
 * through only ever risks re-attempting channels that genuinely were
 * never confirmed sent.
 *
 * <h2>Audit events in the same transaction (backlog #0-84)</h2>
 * Each outcome that has an audit event ({@code NOTIFICATION_SENT},
 * {@code NOTIFICATION_FAILED}, {@code NOTIFICATION_UNDELIVERABLE}) writes it
 * to the service's audit outbox in the transaction of the row it records: the
 * {@code notification_log} row (or the queue entry's status) and its audit
 * event commit together or not at all. Before, the event was sent to Kafka
 * after the commit and without waiting, and was lost whenever Kafka was slow
 * or down. A failure to write it now fails the method, and the row with it.
 */
@Service
public class NotificationPersistenceService {

    private static final String SERVICE_NAME = "notification-service";

    private final NotificationQueueRepository queueRepository;
    private final NotificationLogRepository logRepository;
    private final AuditEventPublisher auditEventPublisher;

    public NotificationPersistenceService(
            NotificationQueueRepository queueRepository,
            NotificationLogRepository logRepository,
            AuditEventPublisher auditEventPublisher) {
        this.queueRepository = queueRepository;
        this.logRepository = logRepository;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Marks {@code entry} SENT and commits immediately. Used both for the
     * "no channels configured" early-return case and the normal
     * end-of-processing case in {@code NotificationService.processEntry} —
     * both are the exact same state transition.
     */
    @Transactional
    public void markSent(NotificationQueueEntry entry) {
        entry.markSent();
        queueRepository.save(entry);
    }

    /**
     * Marks {@code entry} FAILED and commits immediately — used by
     * {@code NotificationScheduler}'s catch block when
     * {@code processEntry} itself throws an unexpected exception (as
     * opposed to an individual channel failure, which is recorded via
     * {@link #recordChannelFailed} and does not fail the whole entry).
     */
    @Transactional
    public void markFailed(NotificationQueueEntry entry, String errorMessage) {
        entry.markFailed(errorMessage);
        queueRepository.save(entry);
    }

    /**
     * Records the first failed recipient lookup on {@code entry} and commits
     * immediately (backlog #0-19); later failures leave the timestamp alone.
     */
    @Transactional
    public void recordLookupFailure(NotificationQueueEntry entry) {
        entry.recordLookupFailure();
        queueRepository.save(entry);
    }

    /**
     * Marks {@code entry} UNDELIVERABLE (backlog #0-18): nobody in the tenant
     * could be notified. Its {@code NOTIFICATION_UNDELIVERABLE} audit event is
     * written in the same transaction (backlog #0-84).
     */
    @Transactional
    public void markUndeliverable(NotificationQueueEntry entry,
                                  UndeliverableReason reason) {
        entry.markUndeliverable(reason);
        queueRepository.save(entry);

        auditEventPublisher.publishIncident(
                entry.getIncidentId(), entry.getTenantId(),
                AuditEventTypes.NOTIFICATION_UNDELIVERABLE, SERVICE_NAME,
                "Notification undeliverable: nobody in the tenant could be "
                        + "notified (" + reason + ")",
                Map.of("eventType", entry.getEventType(), "reason", reason.name()));
    }

    /**
     * Records a delivered notification in {@code notification_log}, with its
     * {@code NOTIFICATION_SENT} audit event in the same transaction (backlog
     * #0-84).
     */
    @Transactional
    public void recordChannelSent(UUID incidentId, String tenantId,
                                  String eventType, int escalationLevel,
                                  String channelName, String recipient,
                                  String subject, String message) {
        logRepository.save(NotificationLog.sent(
                incidentId, tenantId, eventType, escalationLevel,
                channelName, recipient, subject, message));

        auditEventPublisher.publishIncident(
                incidentId, tenantId,
                AuditEventTypes.NOTIFICATION_SENT, SERVICE_NAME,
                String.format("Notification sent via %s to %s", channelName, recipient),
                Map.of("channel", channelName,
                        "recipient", recipient,
                        "eventType", eventType));
    }

    /**
     * Records a failed send in {@code notification_log}, with its
     * {@code NOTIFICATION_FAILED} audit event in the same transaction (backlog
     * #0-84). Every failed send has one now, a channel's own failure and an
     * unexpected error alike: before, only the first was audited. The audit
     * event carries the error through {@link AuditText#error} (one line, at
     * most 500 characters; found in review: an unbounded message could make
     * the event too large to store, and fail this write with it).
     *
     * @param errorMessage why the send failed, written by the platform (the
     *                     caller passes {@link AuditText#unexpected} for an
     *                     exception it did not anticipate, never that
     *                     exception's message); {@code null} is recorded as
     *                     "unknown" ({@code Map.of} refuses a null value)
     */
    @Transactional
    public void recordChannelFailed(UUID incidentId, String tenantId,
                                    String eventType, int escalationLevel,
                                    String channelName, String recipient,
                                    String errorMessage) {
        final String error = errorMessage != null ? errorMessage : "unknown";
        logRepository.save(NotificationLog.failed(
                incidentId, tenantId, eventType, escalationLevel,
                channelName, recipient, error));

        final String auditError = AuditText.error(error);

        auditEventPublisher.publishIncident(
                incidentId, tenantId,
                AuditEventTypes.NOTIFICATION_FAILED, SERVICE_NAME,
                String.format("Notification failed via %s to %s: %s", channelName, recipient, auditError),
                Map.of("channel", channelName,
                        "recipient", recipient,
                        "error", auditError));
    }
}