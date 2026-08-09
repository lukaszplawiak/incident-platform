package com.incidentplatform.postmortem.service;

import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.domain.PostmortemStatus;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Isolates the short, independent database transactions involved in
 * postmortem generation and retry from the long-running Gemini API call
 * that sits between them.
 *
 * <h2>Why this is a separate class, not {@code @Transactional} methods on
 * the calling services</h2>
 * Spring's {@code @Transactional} is implemented via a proxy — calling
 * {@code this.someTransactionalMethod()} from within the same class bypasses
 * the proxy entirely, silently ignoring the annotation (the classic
 * self-invocation pitfall). Putting these methods in a separate, injected
 * {@code @Service} guarantees each call goes through Spring's proxy and
 * actually opens/commits its own short transaction.
 *
 * <h2>Why this matters</h2>
 * Both {@code PostmortemService.generatePostmortem()} and
 * {@code PostmortemRetryScheduler.retryFailedPostmortems()} call
 * {@code geminiClient.generate(prompt)} — an external HTTP call typically
 * taking seconds. The retry scheduler is the more severe case: it processes
 * a batch of FAILED candidates in a single scheduler run, so wrapping the
 * whole loop in one transaction (as it previously did) could hold a database
 * connection for the combined duration of every Gemini call in that batch —
 * potentially tens of seconds across many candidates. Each method here is a
 * short, independent transaction containing only database writes — the
 * connection is acquired and released in milliseconds. Neither
 * {@code generatePostmortem()} nor {@code retryFailedPostmortems()} hold any
 * transaction open while waiting on Gemini.
 */
@Service
public class PostmortemPersistenceService {

    private static final Logger log =
            LoggerFactory.getLogger(PostmortemPersistenceService.class);

    private static final String SERVICE_NAME = "postmortem-service";

    private final PostmortemRepository postmortemRepository;
    private final AuditEventPublisher auditEventPublisher;

    public PostmortemPersistenceService(PostmortemRepository postmortemRepository,
                                        AuditEventPublisher auditEventPublisher) {
        this.postmortemRepository = postmortemRepository;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Creates and persists the initial {@code GENERATING} record.
     * Short transaction — INSERT only, no external calls.
     * <h2>Fixed: the idempotency guard this method's callers document but
     * that never actually existed</h2>
     * {@code IncidentEventConsumer.handleResolved}'s own Javadoc says
     * "If a postmortem already exists for this incident (idempotency
     * guard in PostmortemPersistenceService), this is a no-op" — but no
     * such guard existed anywhere. {@code incident_id} has a DB-level
     * {@code unique = true} constraint, so a genuinely realistic scenario
     * under Kafka's at-least-once delivery (consumer crashes after this
     * INSERT commits but before the offset is acknowledged, so the same
     * INCIDENT_RESOLVED event is redelivered) would throw
     * {@link DataIntegrityViolationException} on the redelivery's
     * {@code save()} call. That exception was never caught here, so it
     * fell through to {@code IncidentEventConsumer}'s generic
     * {@code catch (Exception e)}, which treats it as transient and does
     * NOT acknowledge — Kafka redelivers the same event, hits the same
     * violation, forever, blocking every other message on that partition
     * indefinitely.
     *
     * <p>Fixed the same way {@code oncall-service}'s equivalent
     * check-then-act race was just fixed: an app-level check first
     * ({@code existsByIncidentId} — already defined in the repository,
     * simply never called), backed by catching the DB constraint
     * violation for the rare case the check and the redelivered insert
     * genuinely race. Both paths return the existing record's id rather
     * than treating a duplicate as an error — this consumer's job for
     * that incident is already done.
     */
    @Transactional
    public UUID createGeneratingRecord(UUID incidentId,
                                       String tenantId,
                                       String incidentTitle,
                                       Severity incidentSeverity,
                                       Instant incidentOpenedAt,
                                       Instant incidentResolvedAt,
                                       int durationMinutes) {
        if (postmortemRepository.existsByIncidentId(incidentId)) {
            log.info("Postmortem already exists for incidentId={} — " +
                            "skipping duplicate creation (likely a redelivered " +
                            "Kafka event): tenant={}",
                    incidentId, tenantId);
            return existingIdFor(incidentId, tenantId);
        }

        final Postmortem postmortem = Postmortem.createGenerating(
                incidentId, tenantId, incidentTitle, incidentSeverity,
                incidentOpenedAt, incidentResolvedAt, durationMinutes);

        try {
            postmortemRepository.save(postmortem);
        } catch (DataIntegrityViolationException e) {
            // Race: another attempt (a near-simultaneous redelivery, or the
            // window between the existsByIncidentId check above and this
            // insert) created the record first. The unique constraint on
            // incident_id is what actually caught it — treat it the same
            // way as the check above, not as an error.
            log.info("Postmortem creation raced with an existing record for " +
                            "incidentId={} — treating as already created: tenant={}",
                    incidentId, tenantId);
            return existingIdFor(incidentId, tenantId);
        }

        return postmortem.getId();
    }

    private UUID existingIdFor(UUID incidentId, String tenantId) {
        return postmortemRepository.findByIncidentIdAndTenantId(incidentId, tenantId)
                .map(Postmortem::getId)
                .orElseThrow(() -> new IllegalStateException(
                        "Postmortem for incidentId=" + incidentId +
                                " was reported as existing but could not be " +
                                "found — this should be unreachable"));
    }

    /**
     * Marks the postmortem as {@code DRAFT} with Gemini-generated content
     * and publishes the success audit event. Short transaction — UPDATE only,
     * called after the Gemini call has already completed.
     */
    @Transactional
    public void markDraftAndPublish(UUID postmortemId,
                                    UUID incidentId,
                                    String tenantId,
                                    String content,
                                    String prompt,
                                    int durationMinutes) {
        final Postmortem postmortem = postmortemRepository.getReferenceById(postmortemId);
        postmortem.markDraft(content, prompt);
        postmortemRepository.save(postmortem);

        auditEventPublisher.publishIncident(
                incidentId, tenantId,
                AuditEventTypes.POSTMORTEM_GENERATED, SERVICE_NAME,
                String.format("Postmortem draft generated by Gemini AI. " +
                        "Content length: %d characters.", content.length()),
                Map.of("contentLength", content.length(),
                        "status", PostmortemStatus.DRAFT.name(),
                        "durationMinutes", durationMinutes)
        );
    }

    /**
     * Marks the postmortem as {@code FAILED} and publishes the failure audit
     * event. Short transaction — UPDATE only, called after the Gemini call
     * has already failed.
     *
     * <p>Used both for the initial generation attempt
     * ({@code PostmortemService}) and for a retry attempt that still has
     * remaining attempts left ({@code PostmortemRetryScheduler}) — in both
     * cases the scheduler will pick this record up again later, so this is
     * a transient, self-healing state. See {@link #markPermanentlyFailedAndPublish}
     * for the terminal case.
     */
    @Transactional
    public void markFailedAndPublish(UUID postmortemId,
                                     UUID incidentId,
                                     String tenantId,
                                     String errorMessage) {
        final Postmortem postmortem = postmortemRepository.getReferenceById(postmortemId);
        postmortem.markFailed(errorMessage);
        postmortemRepository.save(postmortem);

        auditEventPublisher.publishIncident(
                incidentId, tenantId,
                AuditEventTypes.POSTMORTEM_FAILED, SERVICE_NAME,
                String.format("Postmortem generation failed: %s", errorMessage),
                Map.of("error", errorMessage,
                        "status", PostmortemStatus.FAILED.name())
        );
    }

    /**
     * Increments the retry counter on an existing FAILED postmortem. Short
     * transaction — UPDATE only, called by the retry scheduler before
     * attempting another Gemini call.
     *
     * <p>Separated from {@link #markFailedAndPublish}/
     * {@link #markPermanentlyFailedAndPublish} because the increment needs
     * to happen and be durably committed <em>before</em> the Gemini call —
     * otherwise a crash mid-retry would leave the attempt uncounted and the
     * scheduler could retry the same record indefinitely past
     * {@code maxRetryAttempts}.
     */
    @Transactional
    public int incrementRetryCount(UUID postmortemId) {
        final Postmortem postmortem = postmortemRepository.getReferenceById(postmortemId);
        postmortem.incrementRetryCount();
        postmortemRepository.save(postmortem);
        return postmortem.getRetryCount();
    }

    /**
     * Marks the postmortem as {@code PERMANENTLY_FAILED} (all retry attempts
     * exhausted) and publishes the corresponding audit event. Short
     * transaction — UPDATE only, called after the final Gemini retry attempt
     * has failed.
     *
     * <p>Uses {@link AuditEventTypes#POSTMORTEM_PERMANENTLY_FAILED} rather
     * than {@link AuditEventTypes#POSTMORTEM_FAILED} — this is a terminal
     * state requiring manual investigation, not a transient failure the
     * scheduler will retry. Keeping the audit event type distinct lets
     * operational alerting and dashboards filter on "needs a human" without
     * being swamped by self-healing transient failures.
     */
    @Transactional
    public void markPermanentlyFailedAndPublish(UUID postmortemId,
                                                UUID incidentId,
                                                String tenantId,
                                                String errorMessage,
                                                int maxRetryAttempts) {
        final Postmortem postmortem = postmortemRepository.getReferenceById(postmortemId);
        postmortem.markPermanentlyFailed(errorMessage);
        postmortemRepository.save(postmortem);

        auditEventPublisher.publishIncident(
                incidentId, tenantId,
                AuditEventTypes.POSTMORTEM_PERMANENTLY_FAILED, SERVICE_NAME,
                String.format("Postmortem generation permanently failed after " +
                        "%d attempts: %s", maxRetryAttempts, errorMessage),
                Map.of("error", errorMessage,
                        "status", PostmortemStatus.PERMANENTLY_FAILED.name(),
                        "attempts", maxRetryAttempts)
        );
    }
}