package com.incidentplatform.postmortem.scheduler;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.postmortem.service.PostmortemPersistenceService;
import com.incidentplatform.postmortem.service.PostmortemPromptBuilder;
import com.incidentplatform.shared.security.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class PostmortemRetryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(PostmortemRetryScheduler.class);

    private final PostmortemRepository postmortemRepository;
    private final GeminiClient geminiClient;
    private final PostmortemPromptBuilder promptBuilder;
    private final PostmortemPersistenceService persistenceService;
    private final int maxRetryAttempts;

    public PostmortemRetryScheduler(PostmortemRepository postmortemRepository,
                                    GeminiClient geminiClient,
                                    PostmortemPromptBuilder promptBuilder,
                                    PostmortemPersistenceService persistenceService,
                                    @Value("${postmortem.max-retry-attempts:3}")
                                    int maxRetryAttempts) {
        this.postmortemRepository = postmortemRepository;
        this.geminiClient = geminiClient;
        this.promptBuilder = promptBuilder;
        this.persistenceService = persistenceService;
        this.maxRetryAttempts = maxRetryAttempts;
    }

    /**
     * Finds FAILED postmortems across all tenants and retries each one.
     *
     * <p>Deliberately NOT {@code @Transactional} at this level — this method
     * loops over potentially many candidates, calling
     * {@code geminiClient.generate(prompt)} (an external HTTP call typically
     * taking seconds) for each. Wrapping the whole loop in one transaction
     * would hold a database connection for the combined duration of every
     * Gemini call in the batch — tens of seconds under a large backlog. Each
     * database write (increment retry count, then mark DRAFT or FAILED/
     * PERMANENTLY_FAILED) is its own short transaction via
     * {@link PostmortemPersistenceService}, called before and after each
     * Gemini call rather than wrapping it.
     *
     * <p>{@code findFailedWithRemainingRetries()} deliberately queries across
     * all tenants in a single statement — this service runs as one shared
     * process against one shared database, so a single cross-tenant query
     * here is the correct, efficient pattern. What matters is that
     * {@link TenantContext} is set for the duration of processing each
     * individual record — see {@link #retryOne}.
     */
    @Scheduled(
            fixedDelayString = "${postmortem.retry-scheduler-interval-ms:300000}",
            initialDelayString = "120000"
    )
    @SchedulerLock(
            name = "postmortem-service:retryFailedPostmortems",
            lockAtMostFor = "9m",
            lockAtLeastFor = "30s"
    )
    public void retryFailedPostmortems() {
        final List<Postmortem> candidates =
                postmortemRepository.findFailedWithRemainingRetries(maxRetryAttempts);

        if (candidates.isEmpty()) {
            log.debug("Postmortem retry check: no FAILED postmortems with remaining retries");
            return;
        }

        log.info("Postmortem retry check: found {} candidates (maxRetryAttempts={})",
                candidates.size(), maxRetryAttempts);

        for (final Postmortem postmortem : candidates) {
            // TenantContext is set for the duration of processing this single
            // record — every log line emitted by retryOne() (and anything it
            // calls) automatically carries the correct tenantId in MDC,
            // matching the pattern already used by every Kafka consumer in
            // this codebase. Cleared in finally so a failure for one tenant's
            // record can never leak its context into the next iteration.
            TenantContext.set(postmortem.getTenantId());
            try {
                retryOne(postmortem);
            } catch (Exception e) {
                log.error("Unexpected error during retry for postmortem: incidentId={}, error={}",
                        postmortem.getIncidentId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    private void retryOne(Postmortem postmortem) {
        final UUID postmortemId = postmortem.getId();
        final UUID incidentId = postmortem.getIncidentId();
        final String tenantId = postmortem.getTenantId();

        // Committed in its own short transaction BEFORE the Gemini call —
        // if the process crashes mid-retry, the attempt is still durably
        // counted, preventing the scheduler from retrying past
        // maxRetryAttempts indefinitely.
        final int retryCount = persistenceService.incrementRetryCount(postmortemId);

        log.info("Retrying postmortem generation: incidentId={}, tenant={}, attempt={}/{}",
                incidentId, tenantId, retryCount, maxRetryAttempts);

        final String prompt = promptBuilder.build(postmortem);

        try {
            // No transaction open during this call — see class Javadoc.
            final String content = geminiClient.generate(prompt);

            persistenceService.markDraftAndPublish(
                    postmortemId, incidentId, tenantId,
                    content, prompt, postmortem.getDurationMinutes());

            log.info("Postmortem retry succeeded: incidentId={}, tenant={}, attempt={}",
                    incidentId, tenantId, retryCount);

        } catch (GeminiException e) {
            final String errorMessage = e.getMessage();

            if (retryCount >= maxRetryAttempts) {
                persistenceService.markPermanentlyFailedAndPublish(
                        postmortemId, incidentId, tenantId,
                        errorMessage, maxRetryAttempts);

                log.error("Postmortem permanently failed after {} attempts: " +
                                "incidentId={}, tenant={}, lastError={}",
                        maxRetryAttempts, incidentId, tenantId, errorMessage);
            } else {
                persistenceService.markFailedAndPublish(
                        postmortemId, incidentId, tenantId, errorMessage);

                log.warn("Postmortem retry failed, will retry later: " +
                                "incidentId={}, attempt={}/{}, error={}",
                        incidentId, retryCount, maxRetryAttempts, errorMessage);
            }
        }
    }
}