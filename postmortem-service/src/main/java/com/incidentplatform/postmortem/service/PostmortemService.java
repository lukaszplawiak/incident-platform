package com.incidentplatform.postmortem.service;

import com.incidentplatform.postmortem.dto.PostmortemDto;
import com.incidentplatform.postmortem.dto.UpdatePostmortemRequest;
import com.incidentplatform.postmortem.domain.Postmortem;
import com.incidentplatform.postmortem.repository.PostmortemRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Application service for postmortem read, update, and review operations.
 *
 * <h2>What this service no longer does</h2>
 * {@code generatePostmortem()} has been removed. Postmortem generation is
 * now driven entirely by {@code PostmortemRetryScheduler} which implements
 * the Outbox Pattern — the Kafka consumer writes a GENERATING record and
 * the scheduler calls Gemini asynchronously. This service is responsible
 * only for read operations, manual content updates, and review sign-off
 * by engineers.
 */
@Service
public class PostmortemService {

    private static final Logger log =
            LoggerFactory.getLogger(PostmortemService.class);

    private static final String SERVICE_NAME = "postmortem-service";

    private final PostmortemRepository postmortemRepository;
    private final AuditEventPublisher auditEventPublisher;

    public PostmortemService(PostmortemRepository postmortemRepository,
                             AuditEventPublisher auditEventPublisher) {
        this.postmortemRepository = postmortemRepository;
        this.auditEventPublisher = auditEventPublisher;
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

        auditEventPublisher.publishIncident(
                incidentId, tenantId,
                AuditEventTypes.POSTMORTEM_UPDATED, SERVICE_NAME,
                "Postmortem content updated by engineer.",
                Map.of("status", postmortem.getStatus().name())
        );

        return PostmortemDto.from(postmortem);
    }

    /**
     * Marks a postmortem REVIEWED — an engineer confirms they have read
     * and approved the content. Completes backlog #50: the REVIEWED
     * status and {@link Postmortem#markReviewed()} existed already, but
     * were unreachable through the API before this method.
     *
     * <p>Only a DRAFT postmortem can be reviewed — there is nothing
     * meaningful to approve while Gemini is still generating content
     * (GENERATING), after a failed attempt with no content yet
     * (FAILED/PERMANENTLY_FAILED), or a second time once already
     * REVIEWED. Rejected with 409 Conflict rather than silently
     * succeeding or silently no-op'ing, matching the same
     * status-guard-with-clear-rejection pattern already established for
     * {@code OncallScheduleService.supersede} in oncall-service.
     *
     * @throws ResourceNotFoundException if no postmortem exists for this incident
     * @throws BusinessException if the postmortem is not currently DRAFT
     */
    @Transactional
    public PostmortemDto markReviewed(UUID incidentId, String tenantId) {
        final Postmortem postmortem = postmortemRepository
                .findByIncidentIdAndTenantId(incidentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Postmortem", incidentId));

        if (!postmortem.isDraft()) {
            throw new BusinessException(
                    ErrorCodes.VALIDATION_FAILED,
                    String.format("Cannot review a postmortem with status %s — " +
                                    "only a DRAFT postmortem can be reviewed",
                            postmortem.getStatus()),
                    HttpStatus.CONFLICT
            );
        }

        postmortem.markReviewed();
        postmortemRepository.save(postmortem);

        log.info("Postmortem reviewed: incidentId={}, tenant={}",
                incidentId, tenantId);

        auditEventPublisher.publishIncident(
                incidentId, tenantId,
                AuditEventTypes.POSTMORTEM_REVIEWED, SERVICE_NAME,
                "Postmortem reviewed and approved by engineer.",
                Map.of("status", postmortem.getStatus().name())
        );

        return PostmortemDto.from(postmortem);
    }
}