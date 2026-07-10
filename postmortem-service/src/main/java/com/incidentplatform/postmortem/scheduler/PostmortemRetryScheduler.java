package com.incidentplatform.postmortem.scheduler;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.config.PostmortemProperties;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.postmortem.service.PostmortemPersistenceService;
import com.incidentplatform.postmortem.service.PostmortemPromptBuilder;
import com.incidentplatform.shared.security.TenantContext;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Processes the postmortem outbox — picks up GENERATING and FAILED
 * postmortems and calls the Gemini AI API to produce draft content.
 *
 * <h2>Outbox Pattern — this scheduler is the processor</h2>
 * The Kafka consumer ({@code IncidentEventConsumer}) writes a GENERATING
 * record to the database and acknowledges immediately. This scheduler is
 * the only component that calls Gemini — in a dedicated scheduled thread,
 * completely decoupled from Kafka consumer throughput.
 *
 * <h2>Two processing paths</h2>
 * <ol>
 *   <li><b>GENERATING</b> — freshly written outbox entries that have not
 *       been processed yet, and stuck records left by a process crash.
 *       Picked up by {@link #processGenerating()}</li>
 *   <li><b>FAILED</b> — records that failed on a previous Gemini attempt
 *       and still have remaining retry budget. Picked up by
 *       {@link #retryFailedPostmortems()}</li>
 * </ol>
 *
 * <h2>Why two separate scheduled methods</h2>
 * GENERATING and FAILED records have different semantics and urgency.
 * GENERATING records should be processed quickly (every 30s by default) —
 * they represent fresh work that just arrived from Kafka. FAILED records
 * are retried on a slower cadence (every 5 minutes by default) to give
 * transient Gemini issues time to clear before the next attempt.
 *
 * <h2>ShedLock</h2>
 * Both methods are protected by ShedLock to prevent duplicate processing
 * when multiple instances of postmortem-service are running.
 */
@Component
@EnableConfigurationProperties(PostmortemProperties.class)
public class PostmortemRetryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(PostmortemRetryScheduler.class);

    private final PostmortemRepository postmortemRepository;
    private final GeminiClient geminiClient;
    private final PostmortemPromptBuilder promptBuilder;
    private final PostmortemPersistenceService persistenceService;
    private final int maxRetryAttempts;
    private final Duration stuckThreshold;

    public PostmortemRetryScheduler(PostmortemRepository postmortemRepository,
                                    GeminiClient geminiClient,
                                    PostmortemPromptBuilder promptBuilder,
                                    PostmortemPersistenceService persistenceService,
                                    PostmortemProperties properties) {
        this.postmortemRepository = postmortemRepository;
        this.geminiClient         = geminiClient;
        this.promptBuilder        = promptBuilder;
        this.persistenceService   = persistenceService;
        this.maxRetryAttempts     = properties.maxRetryAttempts();
        this.stuckThreshold       = properties.stuckThreshold();
    }

    /**
     * Picks up GENERATING outbox entries and calls Gemini for each.
     *
     * <p>Only processes records older than {@code stuckThreshold} (default
     * 2 minutes). This gives the first scheduler run after a consumer write
     * time to complete without racing — a fresh GENERATING record written
     * 10 seconds ago will be picked up on the next run, not immediately.
     *
     * <p>If Gemini succeeds → marks DRAFT.
     * If Gemini fails → marks FAILED (retry scheduler will pick up later).
     */
    @Scheduled(
            fixedDelayString = "${postmortem.generating-scheduler-interval-ms:30000}",
            initialDelayString = "30000"
    )
    @SchedulerLock(
            name = "postmortem-service:processGenerating",
            lockAtMostFor = "4m",
            lockAtLeastFor = "10s"
    )
    public void processGenerating() {
        final Instant threshold = Instant.now().minus(stuckThreshold);
        final List<Postmortem> candidates =
                postmortemRepository.findStuckGenerating(threshold);

        if (candidates.isEmpty()) {
            log.debug("Outbox check: no GENERATING postmortems to process");
            return;
        }

        log.info("Outbox check: found {} GENERATING postmortems to process",
                candidates.size());

        for (final Postmortem postmortem : candidates) {
            TenantContext.set(postmortem.getTenantId());
            try {
                processOne(postmortem);
            } catch (Exception e) {
                log.error("Unexpected error processing GENERATING postmortem: " +
                                "incidentId={}, error={}",
                        postmortem.getIncidentId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * Finds FAILED postmortems across all tenants and retries each one.
     *
     * <p>Deliberately NOT {@code @Transactional} at this level — see class
     * Javadoc. Each database write is its own short transaction via
     * {@link PostmortemPersistenceService}.
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
            TenantContext.set(postmortem.getTenantId());
            try {
                retryOne(postmortem);
            } catch (Exception e) {
                log.error("Unexpected error during retry for postmortem: " +
                                "incidentId={}, error={}",
                        postmortem.getIncidentId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        }
    }

    /**
     * Processes a GENERATING outbox entry — first attempt at calling Gemini.
     * If Gemini fails → marks FAILED so the retry scheduler picks it up.
     */
    private void processOne(Postmortem postmortem) {
        final UUID postmortemId = postmortem.getId();
        final UUID incidentId = postmortem.getIncidentId();
        final String tenantId = postmortem.getTenantId();

        log.info("Processing GENERATING postmortem: incidentId={}, tenant={}",
                incidentId, tenantId);

        final String prompt = promptBuilder.build(postmortem);

        try {
            final String content = geminiClient.generate(prompt);

            persistenceService.markDraftAndPublish(
                    postmortemId, incidentId, tenantId,
                    content, prompt, postmortem.getDurationMinutes());

            log.info("Postmortem generated successfully: incidentId={}, tenant={}, " +
                            "contentLength={}",
                    incidentId, tenantId, content.length());

        } catch (GeminiException e) {
            // First attempt failed — mark FAILED so the retry scheduler
            // picks it up. retryCount stays at 0 (not incremented here —
            // retry scheduler increments before each retry attempt).
            persistenceService.markFailedAndPublish(
                    postmortemId, incidentId, tenantId, e.getMessage());

            log.warn("Postmortem generation failed on first attempt, " +
                            "will be retried: incidentId={}, tenant={}, error={}",
                    incidentId, tenantId, e.getMessage());
        }
    }

    /**
     * Retries a FAILED postmortem — increments retry count, calls Gemini,
     * marks DRAFT or FAILED/PERMANENTLY_FAILED based on the outcome.
     */
    private void retryOne(Postmortem postmortem) {
        final UUID postmortemId = postmortem.getId();
        final UUID incidentId = postmortem.getIncidentId();
        final String tenantId = postmortem.getTenantId();

        // Committed in its own short transaction BEFORE the Gemini call —
        // if the process crashes mid-retry, the attempt is still durably
        // counted, preventing the scheduler from retrying past maxRetryAttempts.
        final int retryCount = persistenceService.incrementRetryCount(postmortemId);

        log.info("Retrying postmortem generation: incidentId={}, tenant={}, attempt={}/{}",
                incidentId, tenantId, retryCount, maxRetryAttempts);

        final String prompt = promptBuilder.build(postmortem);

        try {
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