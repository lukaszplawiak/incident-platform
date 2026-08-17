package com.incidentplatform.escalation.service;

import com.incidentplatform.escalation.domain.EscalationTask;
import com.incidentplatform.escalation.repository.EscalationTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short, independent transactions for {@link EscalationTask} state
 * changes made by {@code EscalationScheduler} — added for backlog #39,
 * extended for backlog #41's retry-attempt tracking.
 *
 * <h2>Why this class exists</h2>
 * {@code EscalationScheduler.checkAndEscalate()} previously carried
 * {@code @Transactional} on the whole method — including the loop that
 * called {@code OncallServiceClient.getCurrentOncall(...)}, a real,
 * blocking HTTP call — holding a database connection open for the
 * combined duration of every on-call lookup in the batch. Under a large
 * backlog (e.g. after a scheduler outage, or a burst of simultaneous
 * incidents) or a slow/degraded oncall-service, this could hold a
 * connection for a long time, risking exhausting the pool.
 *
 * <p>Fixed by removing {@code @Transactional} from
 * {@code checkAndEscalate()} entirely — {@code findDueForEscalation} is
 * already its own short, auto-committing transaction (Spring Data JPA's
 * default behavior for a repository query method called outside any
 * open transaction), and each task's write now goes through this class:
 * a short, independent transaction with no HTTP call in progress while
 * it's open. Same pattern already established in this codebase by
 * {@code AuthEmailPersistenceService} (auth-service),
 * {@code PostmortemPersistenceService} (postmortem-service), and
 * {@code IncidentEventOutboxPersistenceService} (incident-service,
 * backlog #36) — see {@code EscalationScheduler}'s own Javadoc for the
 * comparison across all of them that led to this specific design.
 */
@Service
public class EscalationTaskPersistenceService {

    private final EscalationTaskRepository taskRepository;

    public EscalationTaskPersistenceService(EscalationTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * Marks {@code task} ESCALATED and commits immediately, independent
     * of whatever else {@code EscalationScheduler} is doing for other
     * tasks in the same poll cycle.
     *
     * <p>{@code saveAndFlush} (not {@code save}) forces the
     * {@code @Version} check (backlog #38) to happen synchronously,
     * within this short transaction, rather than being deferred to some
     * later commit point.
     *
     * <p>Deliberately does NOT catch
     * {@link org.springframework.dao.OptimisticLockingFailureException}
     * — lets it propagate to the caller
     * ({@code EscalationScheduler.escalate()}), which specifically
     * recognizes it as "this task was concurrently cancelled" and skips
     * the rest of that task's processing (no Kafka event, no audit
     * entry) rather than treating it as a genuine failure. See that
     * method's own Javadoc for the full account.
     */
    @Transactional
    public void markEscalated(EscalationTask task) {
        task.markEscalated();
        taskRepository.saveAndFlush(task);
    }

    /**
     * Records a failed escalation attempt (backlog #41) and commits
     * immediately, in its own short transaction. Matches
     * {@code IncidentEventOutbox.retryCount}/{@code errorMessage}'s exact
     * shape (incident-service, backlog #36) — same concept, same column
     * naming, applied here.
     *
     * <p>Takes {@code task} directly (like {@link #markEscalated} above),
     * not a lookup-by-id like {@code IncidentEventOutboxPersistenceService
     * .markFailed(UUID, String)} — matched to this class's own sibling
     * method for consistency, since the caller here
     * ({@code EscalationScheduler.checkAndEscalate()}) already has the
     * task object in hand from {@code findDueForEscalation()}, with no
     * intervening step that would make a fresh lookup necessary.
     *
     * <p>Deliberately does NOT catch
     * {@link org.springframework.dao.OptimisticLockingFailureException}
     * either — same reasoning as {@link #markEscalated}. In the rare case
     * where the task was <em>also</em> concurrently modified while
     * {@code escalate()} was failing (e.g. cancelled mid-flight), the
     * caller must not let that secondary conflict abort processing of the
     * rest of the batch — see {@code EscalationScheduler}'s own call site
     * for how that's handled.
     */
    @Transactional
    public void recordFailedAttempt(EscalationTask task, String errorMessage) {
        task.recordFailedAttempt(errorMessage);
        taskRepository.saveAndFlush(task);
    }
}