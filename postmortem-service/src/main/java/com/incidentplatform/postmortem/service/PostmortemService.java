package com.incidentplatform.postmortem.service;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.dto.PostmortemDto;
import com.incidentplatform.postmortem.dto.UpdatePostmortemRequest;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class PostmortemService {

    private static final Logger log =
            LoggerFactory.getLogger(PostmortemService.class);

    private final PostmortemRepository postmortemRepository;
    private final GeminiClient geminiClient;

    public PostmortemService(PostmortemRepository postmortemRepository,
                             GeminiClient geminiClient) {
        this.postmortemRepository = postmortemRepository;
        this.geminiClient = geminiClient;
    }

    @Transactional
    public void generatePostmortem(UUID incidentId,
                                   String tenantId,
                                   String incidentTitle,
                                   String incidentSeverity,
                                   Instant incidentOpenedAt,
                                   Instant incidentResolvedAt,
                                   int durationMinutes) {

        if (postmortemRepository.existsByIncidentId(incidentId)) {
            log.debug("Postmortem already exists for incidentId={}, " +
                    "skipping", incidentId);
            return;
        }

        final Postmortem postmortem = Postmortem.createGenerating(
                incidentId, tenantId, incidentTitle, incidentSeverity,
                incidentOpenedAt, incidentResolvedAt, durationMinutes);
        postmortemRepository.save(postmortem);

        log.info("Generating postmortem: incidentId={}, tenant={}, " +
                        "severity={}, durationMinutes={}",
                incidentId, tenantId, incidentSeverity, durationMinutes);

        try {
            final String prompt = buildPrompt(incidentTitle, incidentSeverity,
                    durationMinutes, incidentOpenedAt, incidentResolvedAt);

            final String content = geminiClient.generate(prompt);

            postmortem.markDraft(content, prompt);
            postmortemRepository.save(postmortem);

            log.info("Postmortem generated successfully: incidentId={}, " +
                            "tenant={}, contentLength={}",
                    incidentId, tenantId, content.length());

        } catch (GeminiException e) {
            postmortem.markFailed(e.getMessage());
            postmortemRepository.save(postmortem);

            log.error("Failed to generate postmortem: incidentId={}, " +
                            "tenant={}, error={}", incidentId, tenantId,
                    e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<PostmortemDto> getPostmortems(String tenantId) {
        return postmortemRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(PostmortemDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PostmortemDto getByIncidentId(UUID incidentId, String tenantId) {
        return postmortemRepository
                .findByIncidentIdAndTenantId(incidentId, tenantId)
                .map(PostmortemDto::from)
                .orElseThrow(() -> new NoSuchElementException(
                        "Postmortem not found for incidentId=" + incidentId));
    }

    @Transactional
    public PostmortemDto updateContent(UUID incidentId,
                                       String tenantId,
                                       UpdatePostmortemRequest request) {
        final Postmortem postmortem = postmortemRepository
                .findByIncidentIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Postmortem not found for incidentId=" + incidentId));

        postmortem.updateContent(request.content());
        postmortemRepository.save(postmortem);

        log.info("Postmortem updated: incidentId={}, tenant={}",
                incidentId, tenantId);

        return PostmortemDto.from(postmortem);
    }

    private String buildPrompt(String title,
                               String severity,
                               int durationMinutes,
                               Instant openedAt,
                               Instant resolvedAt) {
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
                3-5 concrete action items to prevent recurrence, each with a suggested owner role (e.g., Backend Team, SRE Team).
                
                ## Lessons Learned
                Key takeaways from this incident.
                
                Write in a professional, factual tone. Use markdown formatting.
                Keep each section concise and actionable.
                """,
                title, severity, durationMinutes,
                openedAt.toString(), resolvedAt.toString());
    }
}