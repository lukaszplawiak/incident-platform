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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
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
 *
 * <h2>Fixed (backlog #49): concurrent-edit conflict resolution</h2>
 * {@code Postmortem} gained a {@code @Version} column — see its own
 * Javadoc for the full account of the race this closes between an
 * engineer manually editing content ({@code PostmortemService.updateContent})
 * and this scheduler writing back a Gemini result. Both
 * {@link #processGenerating} and {@link #retryFailedPostmortems} catch
 * {@code OptimisticLockingFailureException} specifically, before the
 * generic catch: the engineer's edit wins, and this scheduler simply
 * discards its own Gemini result for that record rather than retrying to
 * overwrite it.
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
    private final int generatingBatchSize;
    private final int retryBatchSize;

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
        this.generatingBatchSize  = properties.generatingBatchSize();
        this.retryBatchSize       = properties.retryBatchSize();
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
     *
     * <h2>Fixed (backlog #48): batch size cap</h2>
     * Previously processed every matching row unconditionally — see
     * {@link PostmortemProperties#generatingBatchSize()}'s Javadoc for why
     * an unbounded batch here was a genuine risk given each item's real
     * Gemini API call cost. Any excess beyond {@code generatingBatchSize}
     * is simply picked up on the next scheduler run rather than attempted
     * in this one.
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
        final List<Postmortem> candidates = postmortemRepository.findStuckGenerating(
                threshold, PageRequest.of(0, generatingBatchSize));

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
            } catch (OptimisticLockingFailureException e) {
                // Fixed (backlog #49): see Postmortem.version's own
                // Javadoc for the full account. The engineer's edit wins
                // — this Gemini result is simply discarded, not retried,
                // since retrying would just race the same edit again (or
                // a subsequent one) with no better outcome. INFO, not
                // ERROR: this is an expected, self-resolving conflict,
                // not a processing failure requiring operator attention.
                log.info("Postmortem was edited concurrently (likely by an " +
                                "engineer) while generating — discarding this " +
                                "Gemini result: incidentId={}",
                        postmortem.getIncidentId());
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
     *
     * <h2>Fixed (backlog #48): batch size cap</h2>
     * Same reasoning as {@link #processGenerating}'s fix — see
     * {@link PostmortemProperties#retryBatchSize()}'s Javadoc.
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
        final List<Postmortem> candidates = postmortemRepository.findFailedWithRemainingRetries(
                maxRetryAttempts, PageRequest.of(0, retryBatchSize));

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
            } catch (OptimisticLockingFailureException e) {
                // Fixed (backlog #49): same reasoning as processGenerating's
                // identical catch — see Postmortem.version's own Javadoc.
                // Covers a conflict at either point retryOne can write:
                // incrementRetryCount (before the Gemini call) or
                // markDraftAndPublish/markFailedAndPublish (after it).
                log.info("Postmortem was edited concurrently (likely by an " +
                                "engineer) during retry — discarding this " +
                                "Gemini result: incidentId={}",
                        postmortem.getIncidentId());
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
     *
     * <h2>Fixed (backlog #80): the whole attempt is now inside one try,
     * catching any Exception</h2>
     * {@code promptBuilder.systemInstruction()}/{@code .userContent(...)}
     * previously ran OUTSIDE this method's try block entirely, and the
     * catch itself was scoped to {@link GeminiException} specifically —
     * either one throwing anything else (e.g. a {@code NullPointerException}
     * from a malformed {@code Postmortem} field) would propagate uncaught
     * straight to {@link #processGenerating}'s own outer catch, which only
     * logs and moves on — this record would stay GENERATING forever,
     * silently retried every {@code generating-scheduler-interval-ms} with
     * no bound and no path to {@code PERMANENTLY_FAILED}, unlike a genuine
     * Gemini failure (correctly marked FAILED, then bounded by
     * {@code maxRetryAttempts} in {@link #retryOne}). Same underlying
     * principle already established for this exact class of gap in
     * {@code IncidentEventConsumer} (backlog #47, this same service): a
     * deny-list of "known, expected" failure types silently leaves
     * everything else outside the bookkeeping it's supposed to be subject
     * to. Inverted the same way — catch broadly, carve out only the one
     * exception type ({@link OptimisticLockingFailureException}) that
     * genuinely needs different handling (re-thrown unchanged, so
     * {@link #processGenerating}'s own catch for it — a routine,
     * self-resolving conflict, not a generation failure — still runs).
     */
    private void processOne(Postmortem postmortem) {
        final UUID postmortemId = postmortem.getId();
        final UUID incidentId = postmortem.getIncidentId();
        final String tenantId = postmortem.getTenantId();

        log.info("Processing GENERATING postmortem: incidentId={}, tenant={}",
                incidentId, tenantId);

        try {
            final String systemInstruction = promptBuilder.systemInstruction();
            final String userContent = promptBuilder.userContent(postmortem);
            final String content = geminiClient.generate(systemInstruction, userContent);

            persistenceService.markDraftAndPublish(
                    postmortemId, incidentId, tenantId,
                    content, auditPromptRecord(systemInstruction, userContent),
                    postmortem.getDurationMinutes());

            log.info("Postmortem generated successfully: incidentId={}, tenant={}, " +
                            "contentLength={}",
                    incidentId, tenantId, content.length());

        } catch (OptimisticLockingFailureException e) {
            // Not a generation failure — an engineer concurrently edited
            // this record via updateContent. Re-thrown unchanged so
            // processGenerating()'s own catch for it (INFO-level, "expected,
            // self-resolving conflict") runs, instead of this method's own
            // catch below wrongly treating it as "Gemini/prompt failed".
            throw e;

        } catch (Exception e) {
            // First attempt failed (Gemini, or prompt-building on this
            // record's own data) — mark FAILED so the retry scheduler
            // picks it up. retryCount stays at 0 (not incremented here —
            // retry scheduler increments before each retry attempt).
            final String errorMessage = e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName();

            persistenceService.markFailedAndPublish(
                    postmortemId, incidentId, tenantId, errorMessage);

            log.warn("Postmortem generation failed on first attempt, " +
                            "will be retried: incidentId={}, tenant={}, error={}",
                    incidentId, tenantId, errorMessage);
        }
    }

    /**
     * Retries a FAILED postmortem — increments retry count, calls Gemini,
     * marks DRAFT or FAILED/PERMANENTLY_FAILED based on the outcome.
     *
     * <h2>Fixed (backlog #80): the maxRetryAttempts ceiling now applies to
     * any failure, not just GeminiException</h2>
     * Same fix, same reasoning as {@link #processOne} — see its Javadoc
     * for the full account. Here specifically: {@code retryCount} was
     * already durably incremented above before this method's own try/catch
     * even began, so a non-{@link GeminiException} failure previously
     * meant that increment was never followed by the corresponding
     * {@code retryCount >= maxRetryAttempts} check — a record could
     * accumulate retries past the configured ceiling via this uncounted
     * path while {@link com.incidentplatform.postmortem.repository.PostmortemRepository
     * #findFailedWithRemainingRetries}'s own {@code retryCount < maxRetryAttempts}
     * filter would eventually stop returning it — stuck in plain FAILED
     * forever, never reaching PERMANENTLY_FAILED, invisible to any
     * "needs a human" dashboard filtering on that status specifically.
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

        try {
            final String systemInstruction = promptBuilder.systemInstruction();
            final String userContent = promptBuilder.userContent(postmortem);
            final String content = geminiClient.generate(systemInstruction, userContent);

            persistenceService.markDraftAndPublish(
                    postmortemId, incidentId, tenantId,
                    content, auditPromptRecord(systemInstruction, userContent),
                    postmortem.getDurationMinutes());

            log.info("Postmortem retry succeeded: incidentId={}, tenant={}, attempt={}",
                    incidentId, tenantId, retryCount);

        } catch (OptimisticLockingFailureException e) {
            // Not a retry failure — an engineer concurrently edited this
            // record. Re-thrown unchanged so retryFailedPostmortems()'s
            // own catch for it runs instead of this method's own catch
            // below wrongly treating it as "Gemini/prompt failed" and
            // consuming retry budget for a conflict that has nothing to
            // do with Gemini.
            throw e;

        } catch (Exception e) {
            final String errorMessage = e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName();

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

    /**
     * Combines the system instruction and user content into a single
     * readable record for {@link Postmortem#getPromptUsed()} — the audit/
     * debugging field showing exactly what was sent to Gemini. Stored as
     * two clearly labeled parts rather than just userContent alone, since
     * the systemInstruction template can change over time (e.g. adjusting
     * the injection-defense framing) and a stored postmortem's audit trail
     * should reflect what was actually sent at generation time, not just
     * today's template.
     */
    private static String auditPromptRecord(String systemInstruction, String userContent) {
        return "[system_instruction]\n" + systemInstruction +
                "\n\n[user content]\n" + userContent;
    }
}