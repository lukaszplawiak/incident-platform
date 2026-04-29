package com.incidentplatform.postmortem.scheduler;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.postmortem.service.PostmortemPromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class PostmortemRetryScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(PostmortemRetryScheduler.class);

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
        final List<Postmortem> failed =
                postmortemRepository.findByStatus("FAILED");

        if (failed.isEmpty()) {
            log.debug("Postmortem retry check: no FAILED postmortems");
            return;
        }

        log.info("Postmortem retry check: found {} FAILED postmortems",
                failed.size());

        for (final Postmortem postmortem : failed) {
            try {
                retryOne(postmortem);
            } catch (Exception e) {
                log.error("Retry failed for postmortem: incidentId={}, error={}",
                        postmortem.getIncidentId(), e.getMessage());
            }
        }
    }

    private void retryOne(Postmortem postmortem) {
        log.info("Retrying postmortem generation: incidentId={}, tenant={}",
                postmortem.getIncidentId(), postmortem.getTenantId());

        try {
            final String prompt = promptBuilder.build(postmortem);
            final String content = geminiClient.generate(prompt);

            postmortem.markDraft(content, prompt);
            postmortemRepository.save(postmortem);

            log.info("Postmortem retry succeeded: incidentId={}, tenant={}",
                    postmortem.getIncidentId(), postmortem.getTenantId());

        } catch (GeminiException e) {
            postmortem.markFailed(e.getMessage());
            postmortemRepository.save(postmortem);

            log.warn("Postmortem retry still failing: incidentId={}, error={}",
                    postmortem.getIncidentId(), e.getMessage());
        }
    }
}