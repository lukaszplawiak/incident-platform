package com.incidentplatform.postmortem.scheduler;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.postmortem.service.PostmortemPromptBuilder;
import com.incidentplatform.shared.security.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class PostmortemRetryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(PostmortemRetryScheduler.class);

    @Value("${postmortem.max-retry-attempts:3}")
    private int maxRetryAttempts;

    private final PostmortemRepository postmortemRepository;
    private final GeminiClient geminiClient;
    private final PostmortemPromptBuilder promptBuilder;

    public PostmortemRetryScheduler(PostmortemRepository postmortemRepository,
                                    GeminiClient geminiClient,
                                    PostmortemPromptBuilder promptBuilder) {
        this.postmortemRepository = postmortemRepository;
        this.geminiClient = geminiClient;
        this.promptBuilder = promptBuilder;
    }

    /**
     * Finds FAILED postmortems across all tenants and retries each one.
     *
     * <p>{@code findFailedWithRemainingRetries()} deliberately queries across
     * all tenants in a single statement — this service runs as one shared
     * process against one shared database (not a database-per-tenant
     * deployment), so a single cross-tenant query here is the correct,
     * efficient pattern, equivalent to {@code EscalationScheduler}'s
     * {@code findDueForEscalation()}. Running N separate per-tenant queries
     * instead would be an N+1-style anti-pattern, not an improvement.
     *
     * <p>What matters is that {@link TenantContext} is set for the duration
     * of processing each individual record — see {@link #retryOne}.
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
    @Transactional
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
        postmortem.incrementRetryCount();

        log.info("Retrying postmortem generation: incidentId={}, tenant={}, attempt={}/{}",
                postmortem.getIncidentId(), postmortem.getTenantId(),
                postmortem.getRetryCount(), maxRetryAttempts);

        try {
            final String prompt = promptBuilder.build(postmortem);
            final String content = geminiClient.generate(prompt);

            postmortem.markDraft(content, prompt);
            postmortemRepository.save(postmortem);

            log.info("Postmortem retry succeeded: incidentId={}, tenant={}, attempt={}",
                    postmortem.getIncidentId(), postmortem.getTenantId(),
                    postmortem.getRetryCount());

        } catch (GeminiException e) {
            final String errorMessage = e.getMessage();

            if (postmortem.getRetryCount() >= maxRetryAttempts) {
                postmortem.markPermanentlyFailed(errorMessage);
                postmortemRepository.save(postmortem);

                log.error("Postmortem permanently failed after {} attempts: " +
                                "incidentId={}, tenant={}, lastError={}",
                        maxRetryAttempts, postmortem.getIncidentId(),
                        postmortem.getTenantId(), errorMessage);
            } else {
                postmortem.markFailed(errorMessage);
                postmortemRepository.save(postmortem);

                log.warn("Postmortem retry failed, will retry later: " +
                                "incidentId={}, attempt={}/{}, error={}",
                        postmortem.getIncidentId(), postmortem.getRetryCount(),
                        maxRetryAttempts, errorMessage);
            }
        }
    }
}