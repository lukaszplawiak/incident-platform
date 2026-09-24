package com.incidentplatform.auth.service;

import com.incidentplatform.auth.domain.SlackWorkspace;
import com.incidentplatform.auth.domain.Team;
import com.incidentplatform.auth.dto.InstallSlackWorkspaceRequest;
import com.incidentplatform.auth.dto.SlackWorkspaceDto;
import com.incidentplatform.auth.dto.SlackWorkspaceInternalDto;
import com.incidentplatform.auth.repository.SlackWorkspaceRepository;
import com.incidentplatform.auth.repository.TeamRepository;
import com.incidentplatform.shared.audit.AuditEventPublisher;
import com.incidentplatform.shared.audit.AuditEventTypes;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.TenantContext;
import com.incidentplatform.shared.security.UserPrincipal;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages each tenant's Slack workspace connection (backlog #0-21).
 *
 * <p>One active workspace per tenant (enforced by the partial unique index
 * in migration V17 and by {@link #install} rejecting a second install with
 * 409 — the admin must revoke the existing one first, no silent rotation,
 * mirroring {@link IntegrationService}'s no-update-endpoint pattern).
 *
 * <p>{@link #getForServiceRead} is the only method that decrypts and
 * returns the raw bot token; it backs the internal, {@code ROLE_SERVICE}-only
 * endpoint notification-service calls (backlog #0-30). Every other method
 * returns {@link SlackWorkspaceDto}, which never carries the token.
 */
@Service
public class SlackWorkspaceService {

    private static final Logger log =
            LoggerFactory.getLogger(SlackWorkspaceService.class);

    /** Partial unique index from V17: one active workspace per tenant. */
    private static final String ACTIVE_TENANT_UNIQUE_INDEX = "uq_slack_workspaces_active_tenant";

    private final SlackWorkspaceRepository slackWorkspaceRepository;
    private final TeamRepository teamRepository;
    private final AesEncryptionService slackEncryptionService;
    private final AuditEventPublisher auditEventPublisher;

    public SlackWorkspaceService(
            SlackWorkspaceRepository slackWorkspaceRepository,
            TeamRepository teamRepository,
            @Qualifier("slackEncryptionService") AesEncryptionService slackEncryptionService,
            AuditEventPublisher auditEventPublisher) {
        this.slackWorkspaceRepository = slackWorkspaceRepository;
        this.teamRepository           = teamRepository;
        this.slackEncryptionService   = slackEncryptionService;
        this.auditEventPublisher      = auditEventPublisher;
    }

    // ── Install ───────────────────────────────────────────────────────────

    @Transactional
    public SlackWorkspaceDto install(InstallSlackWorkspaceRequest request,
                                     UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        if (slackWorkspaceRepository
                .findActiveByTenantIdAndRevokedAtIsNull(tenantId).isPresent()) {
            throw new BusinessException(
                    ErrorCodes.ALREADY_EXISTS,
                    "An active Slack workspace already exists for this tenant — " +
                            "revoke it before installing a new one",
                    HttpStatus.CONFLICT);
        }

        UUID teamId = null;
        if (request.teamId() != null) {
            final Team team = teamRepository
                    .findByIdAndTenantId(request.teamId(), tenantId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Team", request.teamId()));
            teamId = team.getId();
        }

        final String encryptedToken = slackEncryptionService.encrypt(request.botToken());
        final boolean broadcastEnabled = Boolean.TRUE.equals(request.broadcastEnabled());

        final SlackWorkspace workspace = SlackWorkspace.install(
                tenantId, teamId, request.slackTeamId(), encryptedToken,
                request.defaultChannel(), broadcastEnabled);

        final SlackWorkspace saved = saveNewActiveWorkspace(workspace, tenantId);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.SLACK_WORKSPACE_INSTALLED,
                "auth-service",
                principal.userId().toString(),
                "Slack workspace installed: " + request.slackTeamId(),
                Map.of("slackWorkspaceId", saved.getId().toString(),
                        "slackTeamId", request.slackTeamId(),
                        "teamId", teamId != null ? teamId.toString() : "none"));

        log.info("Slack workspace installed: id={}, slackTeamId={}, tenant={}",
                saved.getId(), request.slackTeamId(), tenantId);

        return SlackWorkspaceDto.from(saved);
    }

    // ── Get (admin view) ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SlackWorkspaceDto get() {
        final String tenantId = TenantContext.get();
        return slackWorkspaceRepository
                .findActiveByTenantIdAndRevokedAtIsNull(tenantId)
                .map(SlackWorkspaceDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Slack workspace", tenantId));
    }

    // ── Revoke ────────────────────────────────────────────────────────────

    @Transactional
    public void revoke(UUID id, UserPrincipal principal) {
        final String tenantId = TenantContext.get();

        final SlackWorkspace workspace = slackWorkspaceRepository
                .findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Slack workspace", id));

        if (workspace.isRevoked()) {
            throw new BusinessException(
                    ErrorCodes.BUSINESS_RULE_VIOLATION,
                    "Slack workspace is already revoked",
                    HttpStatus.CONFLICT);
        }

        workspace.revoke();
        slackWorkspaceRepository.save(workspace);

        auditEventPublisher.publishAuth(
                principal.userId(), tenantId,
                AuditEventTypes.SLACK_WORKSPACE_REVOKED,
                "auth-service",
                principal.userId().toString(),
                "Slack workspace revoked: " + workspace.getSlackTeamId(),
                Map.of("slackWorkspaceId", id.toString(),
                        "slackTeamId", workspace.getSlackTeamId()));

        log.info("Slack workspace revoked: id={}, slackTeamId={}, tenant={}, by={}",
                id, workspace.getSlackTeamId(), tenantId, principal.userId());
    }

    // ── Internal, service-to-service read ────────────────────────────────

    /**
     * Decrypts and returns the tenant's active Slack workspace connection,
     * or empty if none is configured. Backs
     * {@code GET /api/v1/internal/slack-workspace} (backlog #0-30) — the
     * tenant comes from {@link TenantContext}, set by {@link
     * com.incidentplatform.shared.security.JwtAuthFilter} from the caller's
     * signed service-token claim, never from a header.
     *
     * <p>Deliberately returns {@link Optional#empty()} rather than throwing
     * for "not configured" — the caller (notification-service's
     * SlackWorkspaceClient) must be able to tell that apart from "auth-service
     * is unreachable" (backlog #0-19's outage-vs-absence distinction, applied
     * here): a 200 with an empty body means "this tenant has no Slack
     * workspace", a transport failure means "we don't know".
     */
    @Transactional(readOnly = true)
    public Optional<SlackWorkspaceInternalDto> getForServiceRead() {
        final String tenantId = TenantContext.get();
        return slackWorkspaceRepository
                .findActiveByTenantIdAndRevokedAtIsNull(tenantId)
                .map(workspace -> new SlackWorkspaceInternalDto(
                        slackEncryptionService.decrypt(workspace.getBotTokenEncrypted()),
                        workspace.getDefaultChannel(),
                        workspace.isBroadcastEnabled(),
                        workspace.getTeamId()));
    }

    /**
     * Inserts the workspace and flushes immediately, so the partial unique index
     * {@code uq_slack_workspaces_active_tenant} (V17) is checked <em>here</em>.
     *
     * <h2>Why flush and translate (backlog #0-21)</h2>
     * The {@code findActive...} check above is not enough on its own: two
     * concurrent installs for one tenant can both pass it before either inserts.
     * The index is the real guarantee. With a plain {@code save()} the violation
     * only fires at commit, after {@code install} has already published a
     * {@code SLACK_WORKSPACE_INSTALLED} audit event for a row that is then rolled
     * back, and it reaches the client as a 500. Flushing first means the audit
     * event is only published for an insert the database accepted, and the loser
     * of the race gets the same 409 as the sequential case. Only this one
     * constraint is translated; any other integrity error is a bug and propagates.
     */
    private SlackWorkspace saveNewActiveWorkspace(SlackWorkspace workspace, String tenantId) {
        try {
            return slackWorkspaceRepository.saveAndFlush(workspace);
        } catch (DataIntegrityViolationException e) {
            if (!violates(e, ACTIVE_TENANT_UNIQUE_INDEX)) {
                throw e;
            }
            log.info("Concurrent Slack workspace install lost the race: tenantId={}", tenantId);
            throw new BusinessException(
                    ErrorCodes.ALREADY_EXISTS,
                    "An active Slack workspace already exists for this tenant — " +
                            "revoke it before installing a new one",
                    HttpStatus.CONFLICT);
        }
    }

    private static boolean violates(DataIntegrityViolationException e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve) {
                return constraintName.equalsIgnoreCase(cve.getConstraintName());
            }
        }
        return false;
    }
}
