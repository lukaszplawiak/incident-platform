package com.incidentplatform.postmortem.scheduler;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.postmortem.service.PostmortemPromptBuilder;
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

    @Scheduled(
            fixedDelayString = "${postmortem.retry-scheduler-interval-ms:300000}",
            initialDelayString = "120000"
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
            try {
                retryOne(postmortem);
            } catch (Exception e) {
                log.error("Unexpected error during retry for postmortem: incidentId={}, error={}",
                        postmortem.getIncidentId(), e.getMessage());
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