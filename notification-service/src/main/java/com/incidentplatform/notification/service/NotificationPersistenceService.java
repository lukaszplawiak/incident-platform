package com.incidentplatform.notification.service;

import com.incidentplatform.notification.domain.NotificationLog;
import com.incidentplatform.notification.domain.NotificationQueueEntry;
import com.incidentplatform.notification.repository.NotificationLogRepository;
import com.incidentplatform.notification.repository.NotificationQueueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class NotificationPersistenceService {

    private final NotificationQueueRepository queueRepository;
    private final NotificationLogRepository logRepository;

    public NotificationPersistenceService(
            NotificationQueueRepository queueRepository,
            NotificationLogRepository logRepository) {
        this.queueRepository = queueRepository;
        this.logRepository = logRepository;
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

    @Transactional
    public void recordChannelSent(UUID incidentId, String tenantId,
                                  String eventType, String channelName,
                                  String recipient, String subject,
                                  String message) {
        logRepository.save(NotificationLog.sent(
                incidentId, tenantId, eventType, channelName,
                recipient, subject, message));
    }

    @Transactional
    public void recordChannelFailed(UUID incidentId, String tenantId,
                                    String eventType, String channelName,
                                    String recipient, String errorMessage) {
        logRepository.save(NotificationLog.failed(
                incidentId, tenantId, eventType, channelName,
                recipient, errorMessage));
    }
}