package com.incidentplatform.postmortem.scheduler;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.Query;
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

    public PostmortemRetryScheduler(PostmortemRepository postmortemRepository,
                                    GeminiClient geminiClient) {
        this.postmortemRepository = postmortemRepository;
        this.geminiClient = geminiClient;
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
                log.error("Retry failed for postmortem: incidentId={}, " +
                                "error={}", postmortem.getIncidentId(),
                        e.getMessage());
            }
        }
    }

    private void retryOne(Postmortem postmortem) {
        log.info("Retrying postmortem generation: incidentId={}, tenant={}",
                postmortem.getIncidentId(), postmortem.getTenantId());

        try {
            final String prompt = buildPrompt(postmortem);
            final String content = geminiClient.generate(prompt);

            postmortem.markDraft(content, prompt);
            postmortemRepository.save(postmortem);

            log.info("Postmortem retry succeeded: incidentId={}, tenant={}",
                    postmortem.getIncidentId(), postmortem.getTenantId());

        } catch (GeminiException e) {
            postmortem.markFailed(e.getMessage());
            postmortemRepository.save(postmortem);

            log.warn("Postmortem retry still failing: incidentId={}, " +
                    "error={}", postmortem.getIncidentId(), e.getMessage());
        }
    }

    private String buildPrompt(Postmortem postmortem) {
        return String.format("""
                You are an experienced SRE (Site Reliability Engineer) writing a postmortem document.
                
                Write a professional postmortem for the following incident:
                
                Title: %s
                Severity: %s
                Duration: %d minutes
                Started: %s
                Resolved: %s
                
                The postmortem should include the following sections:
                
                ## Summary
                A brief 2-3 sentence description of what happened and the impact.
                
                ## Timeline
                A chronological list of key events during the incident.
                
                ## Root Cause
                The technical root cause of the incident.
                
                ## Impact
                Who was affected and how.
                
                ## Resolution
                What was done to resolve the incident.
                
                ## Action Items
                3-5 concrete action items to prevent recurrence, each with a suggested owner role.
                
                ## Lessons Learned
                Key takeaways from this incident.
                
                Write in a professional, factual tone. Use markdown formatting.
                """,
                postmortem.getIncidentTitle(),
                postmortem.getIncidentSeverity(),
                postmortem.getDurationMinutes(),
                postmortem.getIncidentOpenedAt().toString(),
                postmortem.getIncidentResolvedAt().toString());
    }
}