package com.incidentplatform.postmortem.service;

import com.incidentplatform.postmortem.client.GeminiClient;
import com.incidentplatform.postmortem.client.GeminiException;
import com.incidentplatform.postmortem.dto.PostmortemDto;
import com.incidentplatform.postmortem.dto.UpdatePostmortemRequest;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.domain.Severity;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PostmortemService {

    private static final Logger log =
            LoggerFactory.getLogger(PostmortemService.class);

    private static final String SERVICE_NAME = "postmortem-service";

    private final PostmortemRepository postmortemRepository;
    private final GeminiClient geminiClient;
    private final AuditEventPublisher auditEventPublisher;
    private final PostmortemPromptBuilder promptBuilder;
    private final PostmortemPersistenceService persistenceService;

    public PostmortemService(PostmortemRepository postmortemRepository,
                             GeminiClient geminiClient,
                             AuditEventPublisher auditEventPublisher,
                             PostmortemPromptBuilder promptBuilder,
                             PostmortemPersistenceService persistenceService) {
        this.postmortemRepository = postmortemRepository;
        this.geminiClient = geminiClient;
        this.auditEventPublisher = auditEventPublisher;
        this.promptBuilder = promptBuilder;
        this.persistenceService = persistenceService;
    }

    /**
     * Generates a postmortem draft via Gemini AI.
     *
     * <p>Deliberately NOT {@code @Transactional} at this level — the Gemini
     * HTTP call ({@code geminiClient.generate(prompt)}) typically takes
     * seconds and must not hold a database connection for that duration.
     * The three database writes involved (create GENERATING, then DRAFT or
     * FAILED) are each their own short transaction in
     * {@link PostmortemPersistenceService}, called before and after the
     * Gemini call rather than wrapping it.
     *
     * <p>{@code existsByIncidentId} is a plain (non-transactional) read —
     * acceptable here since a duplicate INSERT would fail on the unique
     * constraint on {@code incident_id} regardless; this check is purely an
     * optimization to avoid the wasted Gemini call, not a correctness
     * guarantee.
     */
    public void generatePostmortem(UUID incidentId,
                                   String tenantId,
                                   String incidentTitle,
                                   Severity incidentSeverity,
                                   Instant incidentOpenedAt,
                                   Instant incidentResolvedAt,
                                   int durationMinutes) {

        if (postmortemRepository.existsByIncidentId(incidentId)) {
            log.debug("Postmortem already exists for incidentId={}, skipping",
                    incidentId);
            return;
        }

        final UUID postmortemId = persistenceService.createGeneratingRecord(
                incidentId, tenantId, incidentTitle, incidentSeverity,
                incidentOpenedAt, incidentResolvedAt, durationMinutes);

        log.info("Generating postmortem: incidentId={}, tenant={}, " +
                        "severity={}, durationMinutes={}",
                incidentId, tenantId, incidentSeverity, durationMinutes);

        final String prompt = promptBuilder.build(
                incidentTitle, incidentSeverity.name(),
                durationMinutes, incidentOpenedAt, incidentResolvedAt);

        try {
            final String content = geminiClient.generate(prompt);

            persistenceService.markDraftAndPublish(
                    postmortemId, incidentId, tenantId,
                    content, prompt, durationMinutes);

            log.info("Postmortem generated successfully: incidentId={}, " +
                            "tenant={}, contentLength={}",
                    incidentId, tenantId, content.length());

        } catch (GeminiException e) {
            persistenceService.markFailedAndPublish(
                    postmortemId, incidentId, tenantId, e.getMessage());

            log.error("Failed to generate postmortem: incidentId={}, " +
                            "tenant={}, error={}", incidentId, tenantId,
                    e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Page<PostmortemDto> getPostmortems(String tenantId, Pageable pageable) {
        return postmortemRepository
                .findByTenantIdOrderByCreatedAtDesc(tenantId, pageable)
                .map(PostmortemDto::from);
    }

    @Transactional(readOnly = true)
    public PostmortemDto getByIncidentId(UUID incidentId, String tenantId) {
        return postmortemRepository
                .findByIncidentIdAndTenantId(incidentId, tenantId)
                .map(PostmortemDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Postmortem", incidentId));
    }

    @Transactional
    public PostmortemDto updateContent(UUID incidentId,
                                       String tenantId,
                                       UpdatePostmortemRequest request) {
        final Postmortem postmortem = postmortemRepository
                .findByIncidentIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Postmortem", incidentId));

        postmortem.updateContent(request.content());
        postmortemRepository.save(postmortem);

        log.info("Postmortem updated: incidentId={}, tenant={}",
                incidentId, tenantId);

        auditEventPublisher.publishSystem(
                incidentId, tenantId,
                AuditEventTypes.POSTMORTEM_UPDATED, SERVICE_NAME,
                "Postmortem content updated by engineer.",
                Map.of("status", postmortem.getStatus().name())
        );

        return PostmortemDto.from(postmortem);
    }
}