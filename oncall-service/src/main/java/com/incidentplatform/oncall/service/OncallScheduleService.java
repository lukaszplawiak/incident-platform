package com.incidentplatform.oncall.service;

import com.incidentplatform.oncall.domain.OncallRole;
import com.incidentplatform.oncall.domain.OncallSchedule;
import com.incidentplatform.oncall.dto.CreateOncallScheduleRequest;
import com.incidentplatform.oncall.dto.CurrentOncallResponse;
import com.incidentplatform.oncall.dto.OncallScheduleDto;
import com.incidentplatform.oncall.dto.SlackUserLookupResponse;
import com.incidentplatform.oncall.dto.UpdateOncallScheduleRequest;
import com.incidentplatform.oncall.repository.OncallScheduleRepository;
import com.incidentplatform.shared.exception.BusinessException;
import com.incidentplatform.shared.exception.ErrorCodes;
import com.incidentplatform.shared.exception.ResourceNotFoundException;
import com.incidentplatform.shared.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OncallScheduleService {

    private static final Logger log =
            LoggerFactory.getLogger(OncallScheduleService.class);

    private final OncallScheduleRepository repository;

    public OncallScheduleService(OncallScheduleRepository repository) {
        this.repository = repository;
    }

    /**
     * Fixed: role conversion/validation ({@code OncallRole.valueOf(...)})
     * previously happened AFTER calling {@link
     * OncallScheduleRepository#existsOverlappingForCreate}, which itself
     * was called with the raw {@code String} from the request. That
     * repository method's {@code role} parameter needs an
     * {@code OncallRole} (see its own Javadoc for the full account of why
     * this was a genuine, live production bug — every call to this method
     * would have thrown). Moved the conversion above the overlap check so
     * the repository is always called with a real {@code OncallRole}, and
     * so an invalid role string is rejected with the existing clean
     * {@code VALIDATION_FAILED} response before any database work happens
     * at all — the more correct order regardless of the type-mismatch fix.
     *
     * <h2>TeamRole.MANAGER authorization</h2>
     * {@code principal} added for the Manager role feature — see
     * {@link #requireAdminOrTeamManager}. A {@code null}
     * {@code request.teamId()} (tenant-wide schedule) is only creatable by
     * {@code ROLE_ADMIN}: a team Manager's authority doesn't extend
     * beyond their own team.
     */
    @Transactional
    public OncallScheduleDto create(String tenantId,
                                    CreateOncallScheduleRequest request,
                                    UserPrincipal principal) {
        final OncallRole role;
        try {
            role = OncallRole.valueOf(request.role());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCodes.VALIDATION_FAILED,
                    String.format("Invalid on-call role '%s'. " +
                                    "Allowed values: PRIMARY, SECONDARY, MANAGER",
                            request.role()),
                    HttpStatus.BAD_REQUEST
            );
        }

        requireAdminOrTeamManager(principal, request.teamId());

        final boolean overlapping = repository.existsOverlappingForCreate(
                tenantId,
                request.teamId(),
                role,
                request.startsAt(),
                request.endsAt()
        );

        if (overlapping) {
            throw BusinessException.scheduleOverlap(tenantId, request.role());
        }

        final OncallSchedule schedule = OncallSchedule.create(
                tenantId,
                request.teamId(),
                request.userId(),
                request.userName(),
                request.email(),
                request.phone(),
                request.slackUserId(),
                role,
                request.startsAt(),
                request.endsAt(),
                request.notes()
        );

        // Fixed: existsOverlappingForCreate(...) above is a check-then-act —
        // two concurrent requests for the same tenant+team+role with
        // overlapping windows could both pass that check (neither has
        // committed yet) and both reach save(). The excl_oncall_schedule_overlap
        // constraint (V4 migration) makes the database itself the real
        // guarantee: it will reject the second insert regardless of
        // application-layer timing. Without this catch, that rejection would
        // have surfaced as a raw, unhandled DataIntegrityViolationException —
        // effectively a 500 — instead of the same clean 409 Conflict the
        // (much more commonly hit) application-level check above already
        // produces for this identical business situation.
        try {
            repository.save(schedule);
        } catch (DataIntegrityViolationException e) {
            log.warn("Schedule overlap caught by DB constraint (race condition " +
                            "past the application-level check): tenantId={}, " +
                            "teamId={}, role={}",
                    tenantId, request.teamId(), request.role());
            throw BusinessException.scheduleOverlap(tenantId, request.role());
        }

        log.info("OncallSchedule created: tenantId={}, userId={}, " +
                        "role={}, startsAt={}, endsAt={}",
                tenantId, request.userId(), role,
                request.startsAt(), request.endsAt());

        return OncallScheduleDto.from(schedule);
    }

    /**
     * Replaces an ACTIVE schedule entry with a new one, atomically —
     * backlog #43's supersede pattern. Modeled after how PagerDuty
     * handles editing a scheduled shift: the original row is never
     * deleted, only re-labeled ({@link OncallSchedule#markSuperseded()}),
     * so there is no window — unlike a client-side DELETE-then-POST —
     * where "who is on-call right now" queries would find no one for
     * this slot. Both the old row's status update and the new row's
     * insert happen in this one {@code @Transactional} method: either
     * both commit or neither does.
     *
     * <p>{@code oldId} must currently be ACTIVE — attempting to supersede
     * an already-SUPERSEDED row (e.g. a stale client retrying, or two
     * concurrent edit attempts) is rejected with 409 Conflict rather than
     * silently creating a second, competing replacement.
     *
     * <p>Overlap checking uses {@link OncallScheduleRepository#existsOverlapping}
     * (the {@code excludeId} variant — previously unused dead code; see
     * its own Javadoc) rather than {@code existsOverlappingForCreate},
     * specifically so the old row itself — still ACTIVE at the exact
     * moment this check runs, since it isn't marked SUPERSEDED until the
     * lines immediately after — never counts as a conflict against its
     * own replacement.
     */
    @Transactional
    public OncallScheduleDto supersede(UUID oldId,
                                       String tenantId,
                                       UpdateOncallScheduleRequest request,
                                       UserPrincipal principal) {

        final OncallSchedule old = repository.findByIdAndTenantId(oldId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "OncallSchedule", oldId));

        if (!old.isActive()) {
            throw new BusinessException(
                    ErrorCodes.VALIDATION_FAILED,
                    "Cannot supersede a schedule entry that is not currently ACTIVE " +
                            "(it may have already been edited or is stale)",
                    HttpStatus.CONFLICT
            );
        }

        requireAdminOrTeamManager(principal, old.getTeamId());

        final OncallRole role;
        try {
            role = OncallRole.valueOf(request.role());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(
                    ErrorCodes.VALIDATION_FAILED,
                    String.format("Invalid on-call role '%s'. " +
                                    "Allowed values: PRIMARY, SECONDARY, MANAGER",
                            request.role()),
                    HttpStatus.BAD_REQUEST
            );
        }

        final boolean overlapping = repository.existsOverlapping(
                tenantId,
                request.teamId(),
                role,
                request.startsAt(),
                request.endsAt(),
                oldId
        );

        if (overlapping) {
            throw BusinessException.scheduleOverlap(tenantId, request.role());
        }

        final OncallSchedule replacement = OncallSchedule.createSuperseding(
                oldId,
                tenantId,
                request.teamId(),
                request.userId(),
                request.userName(),
                request.email(),
                request.phone(),
                request.slackUserId(),
                role,
                request.startsAt(),
                request.endsAt(),
                request.notes()
        );

        old.markSuperseded();

        // Same reasoning as create()'s identical try/catch — the
        // application-level existsOverlapping check above is still a
        // check-then-act, and excl_oncall_schedule_overlap (now scoped to
        // status = ACTIVE, migration V5) is the real, race-proof
        // guarantee underneath it.
        try {
            repository.save(old);
            repository.save(replacement);
        } catch (DataIntegrityViolationException e) {
            log.warn("Schedule overlap caught by DB constraint on supersede " +
                            "(race condition past the application-level check): " +
                            "oldId={}, tenantId={}, teamId={}, role={}",
                    oldId, tenantId, request.teamId(), request.role());
            throw BusinessException.scheduleOverlap(tenantId, request.role());
        }

        log.info("OncallSchedule superseded: oldId={}, newId={}, tenantId={}, " +
                        "userId={}, role={}",
                oldId, replacement.getId(), tenantId, request.userId(), role);

        return OncallScheduleDto.from(replacement);
    }

    /**
     * Fixed: previously passed the raw {@code role} String straight to
     * {@link OncallScheduleRepository#findCurrentOncallByRole}, which
     * needs an {@code OncallRole} (see that method's Javadoc for the full
     * account — this was a live production bug affecting one of the two
     * most-used endpoints in this service).
     *
     * <p>An unparseable {@code role} is treated the same as "no schedule
     * found" ({@code Optional.empty()}) rather than thrown as a validation
     * error: this is a read/lookup path, called by notification-service
     * and escalation-service behind their own circuit breakers — those
     * callers always pass a fixed, valid role string in practice, so a
     * malformed value reaching here is a defensive edge case, not a
     * realistic normal-operation one, and failing softly (no on-call
     * found) is safer than surfacing a new 5xx failure mode to
     * circuit-breaker-wrapped callers that don't distinguish "bad input"
     * from "service unavailable".
     */
    @Transactional(readOnly = true)
    public Optional<CurrentOncallResponse> getCurrentOncall(
            String tenantId, String role) {
        return parseRole(role)
                .flatMap(parsedRole -> repository.findCurrentOncallByRole(
                        tenantId, parsedRole, Instant.now()))
                .map(CurrentOncallResponse::from);
    }

/**
 * Returns the current on-call person for a specific team and role.
 * Primary query used by EscalationScheduler via HTTP.
 *
 * <p>Fixed: same {@code String}-to-{@code OncallRole} conversion fix
 * and same "unparseable role treated as not-found" reasoning as
 * {@link #getCurrentOncall} — see its Javadoc.
 *
 * @return empty when no active schedule found for this team/role
 */
@Transactional(readOnly = true)
public Optional<CurrentOncallResponse> getCurrentOncallForTeam(
        String tenantId, UUID teamId, String role) {
    return parseRole(role)
            .flatMap(parsedRole -> repository.findCurrentOncallByTeamAndRole(
                    tenantId, teamId, parsedRole, Instant.now()))
            .map(CurrentOncallResponse::from);
}

    private static Optional<OncallRole> parseRole(String role) {
        try {
            return Optional.of(OncallRole.valueOf(role));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    @Transactional(readOnly = true)
    public List<CurrentOncallResponse> getAllCurrentOncallForTeam(
            String tenantId, UUID teamId) {
        return repository.findAllCurrentOncallForTeam(
                        tenantId, teamId, Instant.now())
                .stream()
                .map(CurrentOncallResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CurrentOncallResponse> getAllCurrentOncall(String tenantId) {
        return repository.findAllCurrentOncall(tenantId, Instant.now())
                .stream()
                .map(CurrentOncallResponse::from)
                .toList();
    }

    /**
     * Returns a paginated list of all schedules for the given tenant.
     * Previously returned an unbounded {@code List} — replaced with
     * {@code Page} to avoid loading all rows into memory for tenants
     * with years of scheduling history.
     */
    @Transactional(readOnly = true)
    public Page<OncallScheduleDto> getSchedules(String tenantId,
                                                Pageable pageable) {
        return repository.findByTenantIdOrderByStartsAtDesc(tenantId, pageable)
                .map(OncallScheduleDto::from);
    }

    @Transactional(readOnly = true)
    public OncallScheduleDto getById(UUID id, String tenantId) {
        return repository.findByIdAndTenantId(id, tenantId)
                .map(OncallScheduleDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "OncallSchedule", id));
    }

    /**
     * Looks up the on-call schedule entry for a given Slack user within
     * the specified tenant. Used by {@code notification-service} when
     * resolving a Slack ACK button click to an internal system user ID.
     *
     * <p>The {@code tenantId} parameter is mandatory — without it the query
     * would search all tenants, and two tenants sharing the same Slack
     * workspace could have colliding {@code slackUserId} values.
     */
    @Transactional(readOnly = true)
    public Optional<SlackUserLookupResponse> findBySlackUserId(
            String tenantId, String slackUserId) {
        return repository.findByTenantIdAndSlackUserId(tenantId, slackUserId)
                .stream()
                .findFirst()
                .map(schedule -> new SlackUserLookupResponse(
                        schedule.getUserId(),
                        schedule.getUserName(),
                        schedule.getTenantId(),
                        schedule.getSlackUserId()
                ));
    }

    /**
     * <h2>TeamRole.MANAGER authorization</h2>
     * {@code principal} added for the Manager role feature. The schedule
     * is loaded first regardless (needed for the 404 case anyway), so its
     * real {@code teamId} is used for the check — see
     * {@link #requireAdminOrTeamManager}.
     *
     * <h2>Fixed (backlog #44): physical DELETE replaced with soft-cancel</h2>
     * Previously called {@code repository.delete(schedule)} — a genuine,
     * physical {@code DELETE FROM}. Inconsistent with the rest of this
     * platform's convention that time-bound domain entities are never
     * physically removed, only moved to a terminal status (see
     * {@code EscalationTaskStatus.CANCELLED}, and {@code Incident}, which
     * has no delete endpoint at all). Also a genuine, reachable bug
     * introduced by backlog #43: a SUPERSEDED row is referenced by its
     * replacement's {@code supersedes_id} foreign key, so physically
     * deleting a schedule that had ever been edited would be rejected by
     * that constraint with an unhandled
     * {@code DataIntegrityViolationException} (500) — under the old
     * implementation, a schedule could never actually be deleted once it
     * had been superseded even once.
     *
     * <p>Now calls {@link OncallSchedule#cancel()} and saves, instead —
     * same mechanics as {@link #supersede}'s {@code markSuperseded()}.
     * The HTTP contract is unchanged: still {@code DELETE /schedules/{id}},
     * still 204 on success — this is an internal implementation change,
     * not an API change.
     *
     * <h2>New: rejects cancelling an already-elapsed schedule</h2>
     * Matches how PagerDuty handles the same operation on its own
     * overrides ("you can only delete present or future overrides") —
     * see {@link OncallSchedule#hasFullyElapsed} for the exact rule. A
     * schedule whose window is still in progress, or hasn't started yet,
     * can still be cancelled (e.g. someone's on-call shift ending early
     * is a legitimate, common case) — only one that has already fully
     * run its course is rejected, with 409 Conflict, since there is no
     * sensible "the future didn't happen" meaning for a past entry.
     */
    @Transactional
    public void cancel(UUID id, String tenantId, UserPrincipal principal) {
        final OncallSchedule schedule = repository
                .findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "OncallSchedule", id));

        requireAdminOrTeamManager(principal, schedule.getTeamId());

        if (schedule.hasFullyElapsed(Instant.now())) {
            throw new BusinessException(
                    ErrorCodes.VALIDATION_FAILED,
                    "Cannot cancel a schedule entry whose window has already " +
                            "fully elapsed — only present or future entries can " +
                            "be cancelled",
                    HttpStatus.CONFLICT
            );
        }

        schedule.cancel();
        repository.save(schedule);

        log.info("OncallSchedule cancelled: id={}, tenantId={}", id, tenantId);
    }

    /**
     * Authorizes a team-scoped write (create/delete an on-call schedule):
     * allowed for {@code ROLE_ADMIN} (any team, and tenant-wide schedules
     * where {@code teamId} is {@code null}), or a user holding
     * {@code TeamRole.MANAGER} for the specific {@code teamId} — checked
     * via {@link UserPrincipal#isManagerOf}, backed by the
     * {@code managedTeamIds} JWT claim (populated by auth-service's
     * TeamMemberRepository at login/token-refresh time — no cross-service
     * call needed here).
     *
     * <p>No additional guardrail beyond this — e.g. no check that the
     * Manager also holds {@code ROLE_RESPONDER} — matching the same
     * "soft operational assumption, not hard-validated" product decision
     * documented on {@code TeamController}.
     */
    private void requireAdminOrTeamManager(UserPrincipal principal, UUID teamId) {
        if (principal.hasRole("ROLE_ADMIN")) {
            return;
        }
        if (principal.isManagerOf(teamId)) {
            return;
        }
        throw new BusinessException(
                ErrorCodes.FORBIDDEN,
                "Requires ROLE_ADMIN, or TeamRole.MANAGER for this schedule's team",
                HttpStatus.FORBIDDEN
        );
    }
}